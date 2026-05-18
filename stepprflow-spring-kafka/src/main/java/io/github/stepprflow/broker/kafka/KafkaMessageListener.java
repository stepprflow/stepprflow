package io.github.stepprflow.broker.kafka;

import io.github.stepprflow.core.event.WorkflowMessageEvent;
import io.github.stepprflow.core.model.WorkflowMessage;
import io.github.stepprflow.core.model.WorkflowRegistrationRequest;
import io.github.stepprflow.core.model.WorkflowStatus;
import io.github.stepprflow.core.service.StepExecutor;
import io.github.stepprflow.core.service.WorkflowRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Kafka listener for workflow messages.
 * Listens to registered workflow topics and delegates to StepExecutor.
 * This bean is created by KafkaBrokerAutoConfiguration.
 */
@RequiredArgsConstructor
@Slf4j
public class KafkaMessageListener {

    private final StepExecutor stepExecutor;
    private final WorkflowRegistry registry;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Listen to all registered workflow topics.
     */
    @KafkaListener(
            topicPattern = "${stepprflow.kafka.topic-pattern:.*}",
            containerFactory = "workflowKafkaListenerContainerFactory",
            groupId = "${stepprflow.kafka.consumer.group-id:stepprflow-workflow-processor}"
    )
    public void onMessage(ConsumerRecord<String, WorkflowMessage> record, Acknowledgment ack) {
        // Skip registration messages — handled by the monitoring module
        if (WorkflowRegistrationRequest.REGISTRATION_TOPIC.equals(record.topic())) {
            ack.acknowledge();
            return;
        }

        // Skip topics not registered as workflows in this service.
        // Without this guard, the default topicPattern=".*" makes every consumer
        // receive ALL workflow messages on the cluster, polluting logs and
        // triggering side-effects (eventPublisher + StepExecutor.execute) on
        // messages destined for other services.
        if (!registry.getTopics().contains(record.topic())) {
            if (log.isDebugEnabled()) {
                log.debug("Ignoring message on unregistered topic: {}", record.topic());
            }
            ack.acknowledge();
            return;
        }

        WorkflowMessage message = record.value();

        if (message == null) {
            log.warn("Received null message on topic {}", record.topic());
            ack.acknowledge();
            return;
        }

        log.info("Received workflow message: topic={}, executionId={}, step={}, status={}",
                record.topic(), message.getExecutionId(), message.getCurrentStep(), message.getStatus());

        // Publish event for monitoring/persistence
        eventPublisher.publishEvent(new WorkflowMessageEvent(this, message));

        // Only process PENDING or IN_PROGRESS messages
        if (message.getStatus() == WorkflowStatus.PENDING ||
            message.getStatus() == WorkflowStatus.IN_PROGRESS) {
            try {
                stepExecutor.execute(message);
                ack.acknowledge();
            } catch (Exception e) {
                log.error("Error processing message: {}", e.getMessage(), e);
                // Don't acknowledge - message will be redelivered
            }
        } else {
            log.debug("Skipping message with status {}", message.getStatus());
            ack.acknowledge();
        }
    }
}

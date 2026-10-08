package io.github.stepprflow.dashboard.listener;

import io.github.stepprflow.core.event.WorkflowMessageEvent;
import io.github.stepprflow.core.model.WorkflowMessage;
import io.github.stepprflow.core.model.WorkflowRegistrationRequest;
import io.github.stepprflow.monitor.service.RegistrationMessageHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka listener for the monitoring dashboard.
 *
 * This listener only receives workflow messages for monitoring purposes.
 * It does NOT execute workflow steps - that is the responsibility of the
 * microservices using stepprflow-agent.
 *
 * The listener publishes WorkflowMessageEvent which are consumed by:
 * - ExecutionPersistenceService (saves to MongoDB)
 * - WorkflowMetricsListener (updates in-memory metrics)
 */
@Component
@ConditionalOnClass(name = "org.apache.kafka.clients.consumer.ConsumerRecord")
@RequiredArgsConstructor
@Slf4j
public class MonitoringKafkaListener {

    private final ApplicationEventPublisher eventPublisher;
    private final RegistrationMessageHandler registrationHandler;

    /**
     * Listen to all workflow topics for monitoring.
     * Uses a different consumer group than the workflow processors.
     * <p>
     * The pattern is resolved by {@code monitoringTopicPatternResolver}
     * rather than a plain {@code ${...}} placeholder, with this priority:
     * the dedicated {@code stepprflow.dashboard.topic-pattern}, then the
     * legacy {@code stepprflow.kafka.topic-pattern} (backward compatible),
     * then a pattern derived from {@code stepprflow.kafka.workflow-topics}
     * covering every stepprflow suffix for each base topic, then the raw
     * default {@code ".*"}. See {@code MonitoringTopicPatternResolver}.
     * </p>
     */
    @KafkaListener(
            topicPattern = "#{monitoringTopicPatternResolver.effectivePattern}",
            containerFactory = "workflowKafkaListenerContainerFactory",
            groupId = "${stepprflow.dashboard.consumer.group-id:stepprflow-monitoring}"
    )
    public void onMessage(ConsumerRecord<String, WorkflowMessage> record, Acknowledgment ack) {
        WorkflowMessage message = record.value();

        if (message == null) {
            log.warn("Received null message on topic {}", record.topic());
            ack.acknowledge();
            return;
        }

        // Delegate registration messages to the registration handler
        if (WorkflowRegistrationRequest.REGISTRATION_TOPIC.equals(record.topic())) {
            registrationHandler.handle(message);
            ack.acknowledge();
            return;
        }

        log.debug("Monitoring received: topic={}, executionId={}, step={}, status={}",
                record.topic(), message.getExecutionId(), message.getCurrentStep(), message.getStatus());

        // Publish event for monitoring/persistence only
        // The ExecutionPersistenceService and WorkflowMetricsListener will handle it
        eventPublisher.publishEvent(new WorkflowMessageEvent(this, message));

        ack.acknowledge();
    }
}

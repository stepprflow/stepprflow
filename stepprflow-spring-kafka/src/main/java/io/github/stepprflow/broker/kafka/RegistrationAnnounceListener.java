package io.github.stepprflow.broker.kafka;

import io.github.stepprflow.core.model.WorkflowMessage;
import io.github.stepprflow.core.model.WorkflowRegistrationRequest;
import io.github.stepprflow.core.registration.WorkflowRegistrationClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Listens on the registration topic for the monitor's {@code ANNOUNCE} and asks
 * this service to re-register.
 *
 * <p>The main {@link KafkaMessageListener} is scoped to the service's own
 * workflow topics and deliberately skips the registration topic, so this
 * dedicated listener subscribes to {@link WorkflowRegistrationRequest#REGISTRATION_TOPIC}
 * explicitly — independently of {@code stepprflow.kafka.topic-pattern}. Each
 * instance uses its own consumer group (unique per JVM) so an ANNOUNCE reaches
 * <em>every</em> running instance, not just one group member.</p>
 *
 * <p>The per-JVM group is brand new on every start, so {@code auto.offset.reset}
 * is pinned to {@code latest} here: without it the default {@code earliest} would
 * replay the whole registration-topic history on each start, re-registering once
 * per historical ANNOUNCE and churning ephemeral consumer groups. We only care
 * about announcements that arrive while this instance is live.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class RegistrationAnnounceListener {

    private final WorkflowRegistrationClient registrationClient;

    @KafkaListener(
            topics = WorkflowRegistrationRequest.REGISTRATION_TOPIC,
            containerFactory = "workflowKafkaListenerContainerFactory",
            groupId = "stepprflow-announce-#{T(java.util.UUID).randomUUID().toString()}",
            properties = {"auto.offset.reset=latest"}
    )
    public void onRegistration(final ConsumerRecord<String, WorkflowMessage> record,
            final Acknowledgment ack) {
        try {
            registrationClient.onRegistrationMessage(record.value());
        } catch (Exception e) {
            log.warn("Failed to handle registration-topic message: {}", e.getMessage());
        } finally {
            // Always ack: a missed announcement is harmless (the next one, or a
            // heartbeat-driven discovery, recovers), and we must not block the
            // partition retrying an announcement.
            ack.acknowledge();
        }
    }
}

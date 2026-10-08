package io.github.stepprflow.monitor.service;

import io.github.stepprflow.core.broker.MessageBroker;
import io.github.stepprflow.core.model.WorkflowMessage;
import io.github.stepprflow.core.model.WorkflowRegistrationRequest;
import io.github.stepprflow.core.model.WorkflowStatus;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Announces the monitor's presence on the registration topic so every running
 * service re-sends its full registration (step definitions included).
 *
 * <p>Service registration is one-shot at service startup. A monitor that
 * (re)starts after the services never receives those REGISTER messages, so its
 * workflow catalogue is incomplete — workflows show up (discovered from
 * executions) but without their step list. Broadcasting an
 * {@link WorkflowRegistrationRequest#ACTION_ANNOUNCE} on startup asks the
 * services to re-register, closing that gap without a manual service restart.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MonitorAnnouncer {

    private final MessageBroker messageBroker;

    /** Broadcasts the announcement once the monitor is fully started. */
    @EventListener(ApplicationReadyEvent.class)
    public void announceOnStartup() {
        announce();
    }

    /**
     * Publishes a single {@code ANNOUNCE} message on the registration topic.
     * Failures are swallowed (logged) — a missed announcement only means the
     * catalogue stays as-is until the next heartbeat-driven discovery or the
     * next announcement, never a crash.
     */
    public void announce() {
        try {
            final Map<String, Object> metadata = new HashMap<>();
            metadata.put(WorkflowRegistrationRequest.METADATA_ACTION,
                    WorkflowRegistrationRequest.ACTION_ANNOUNCE);

            final WorkflowMessage message = WorkflowMessage.builder()
                    .executionId(UUID.randomUUID().toString())
                    .topic(WorkflowRegistrationRequest.REGISTRATION_TOPIC)
                    .status(WorkflowStatus.COMPLETED)
                    .metadata(metadata)
                    .build();

            messageBroker.send(WorkflowRegistrationRequest.REGISTRATION_TOPIC, message);
            log.info("Published monitor ANNOUNCE — requesting re-registration from all services");
        } catch (Exception e) {
            log.warn("Monitor ANNOUNCE failed: {}", e.getMessage());
        }
    }
}

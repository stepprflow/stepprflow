package io.github.stepprflow.core.registration;

import io.github.stepprflow.core.broker.MessageBroker;
import io.github.stepprflow.core.model.WorkflowDefinition;
import io.github.stepprflow.core.model.WorkflowMessage;
import io.github.stepprflow.core.model.WorkflowRegistrationRequest;
import io.github.stepprflow.core.model.WorkflowStatus;
import io.github.stepprflow.core.service.WorkflowRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Client that registers workflow definitions with the monitoring dashboard
 * via the shared message broker (Kafka or RabbitMQ).
 *
 * <p>At startup (after a 5-second delay), collects all workflow definitions
 * from {@link WorkflowRegistry} and sends them on the
 * {@code stepprflow.registration} topic. Maintains a heartbeat and
 * sends a deregistration message on shutdown.
 */
@Slf4j
public class WorkflowRegistrationClient {

    private static final int INITIAL_DELAY_SECONDS = 5;

    private final RegistrationProperties properties;
    private final WorkflowRegistry workflowRegistry;
    private final MessageBroker messageBroker;
    private final String appName;
    private final int serverPort;
    private final String instanceId;

    private ScheduledExecutorService initExecutor;
    private volatile boolean registered;

    /**
     * Creates a new WorkflowRegistrationClient.
     *
     * @param properties       the registration properties
     * @param workflowRegistry the workflow registry
     * @param messageBroker    the message broker
     * @param appName          the application name
     * @param serverPort       the server port
     */
    public WorkflowRegistrationClient(
            final RegistrationProperties properties,
            final WorkflowRegistry workflowRegistry,
            final MessageBroker messageBroker,
            @Value("${spring.application.name:unknown}") final String appName,
            @Value("${server.port:8080}") final int serverPort) {
        this.properties = properties;
        this.workflowRegistry = workflowRegistry;
        this.messageBroker = messageBroker;
        this.appName = appName;
        this.serverPort = serverPort;
        this.instanceId = UUID.randomUUID().toString();
    }

    /**
     * Schedules delayed registration after startup.
     */
    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.info("Workflow registration is disabled");
            return;
        }

        initExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "stepprflow-registration");
            thread.setDaemon(true);
            return thread;
        });

        initExecutor.schedule(this::registerWorkflows,
                INITIAL_DELAY_SECONDS, TimeUnit.SECONDS);

        log.info("Workflow registration scheduled in {}s via {} broker",
                INITIAL_DELAY_SECONDS, messageBroker.getBrokerType());
    }

    /**
     * Sends a heartbeat message via the broker.
     */
    @Scheduled(fixedDelayString = "${stepprflow.registration.heartbeat-interval-seconds:30}000")
    public void heartbeat() {
        if (!registered) {
            return;
        }

        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(WorkflowRegistrationRequest.METADATA_ACTION,
                    WorkflowRegistrationRequest.ACTION_HEARTBEAT);
            metadata.put(WorkflowRegistrationRequest.METADATA_INSTANCE_ID, instanceId);

            WorkflowMessage message = WorkflowMessage.builder()
                    .executionId(UUID.randomUUID().toString())
                    .topic(WorkflowRegistrationRequest.REGISTRATION_TOPIC)
                    .serviceName(appName)
                    .status(WorkflowStatus.COMPLETED)
                    .metadata(metadata)
                    .build();

            messageBroker.send(WorkflowRegistrationRequest.REGISTRATION_TOPIC, message);
            log.debug("Heartbeat sent for {} (instance: {})", appName, instanceId);
        } catch (Exception e) {
            log.warn("Heartbeat failed: {}", e.getMessage());
        }
    }

    /**
     * Handles an inbound message on the registration topic. When the monitor
     * publishes an {@link WorkflowRegistrationRequest#ACTION_ANNOUNCE}, this
     * service re-sends its full registration (step definitions included) so a
     * monitor that started after the service — and thus missed the one-time
     * startup REGISTER — rebuilds its workflow catalogue without a restart.
     *
     * <p>Every other action is ignored (our own REGISTER/HEARTBEAT/DEREGISTER
     * and other services' messages), so there is no feedback loop or
     * re-registration storm.</p>
     *
     * @param message the registration-topic message (may be null)
     */
    public void onRegistrationMessage(final WorkflowMessage message) {
        if (message == null || message.getMetadata() == null) {
            return;
        }
        final Object action =
                message.getMetadata().get(WorkflowRegistrationRequest.METADATA_ACTION);
        if (WorkflowRegistrationRequest.ACTION_ANNOUNCE.equals(action)) {
            log.info("Monitor ANNOUNCE received — re-registering workflows for {}", appName);
            registerWorkflows();
        }
    }

    /**
     * Sends a deregistration message on shutdown.
     */
    @PreDestroy
    public void shutdown() {
        if (initExecutor != null) {
            initExecutor.shutdownNow();
        }

        if (!registered) {
            return;
        }

        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(WorkflowRegistrationRequest.METADATA_ACTION,
                    WorkflowRegistrationRequest.ACTION_DEREGISTER);
            metadata.put(WorkflowRegistrationRequest.METADATA_INSTANCE_ID, instanceId);

            WorkflowMessage message = WorkflowMessage.builder()
                    .executionId(UUID.randomUUID().toString())
                    .topic(WorkflowRegistrationRequest.REGISTRATION_TOPIC)
                    .serviceName(appName)
                    .status(WorkflowStatus.COMPLETED)
                    .metadata(metadata)
                    .build();

            messageBroker.sendSync(WorkflowRegistrationRequest.REGISTRATION_TOPIC, message);
            log.info("Deregistration sent for {} (instance: {})", appName, instanceId);
        } catch (Exception e) {
            log.warn("Deregistration failed: {}", e.getMessage());
        }
    }

    /**
     * Registers all workflow definitions via the broker.
     */
    void registerWorkflows() {
        List<WorkflowDefinition> definitions = workflowRegistry.getAllDefinitions();
        if (definitions.isEmpty()) {
            log.info("No workflow definitions to register");
            return;
        }

        WorkflowRegistrationRequest request = buildRegistrationRequest(definitions);

        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(WorkflowRegistrationRequest.METADATA_ACTION,
                    WorkflowRegistrationRequest.ACTION_REGISTER);
            metadata.put(WorkflowRegistrationRequest.METADATA_INSTANCE_ID, instanceId);

            WorkflowMessage message = WorkflowMessage.builder()
                    .executionId(UUID.randomUUID().toString())
                    .topic(WorkflowRegistrationRequest.REGISTRATION_TOPIC)
                    .serviceName(appName)
                    .status(WorkflowStatus.COMPLETED)
                    .payload(request)
                    .metadata(metadata)
                    .build();

            messageBroker.send(WorkflowRegistrationRequest.REGISTRATION_TOPIC, message);
            registered = true;
            log.info("Registered {} workflow(s) via {} broker",
                    definitions.size(), messageBroker.getBrokerType());
        } catch (Exception e) {
            log.warn("Registration failed: {}", e.getMessage());
        }
    }

    private WorkflowRegistrationRequest buildRegistrationRequest(
            final List<WorkflowDefinition> definitions) {
        List<WorkflowRegistrationRequest.WorkflowInfo> workflows = definitions.stream()
                .map(this::toWorkflowInfo)
                .toList();

        return WorkflowRegistrationRequest.builder()
                .serviceName(appName)
                .instanceId(instanceId)
                .host(resolveHost())
                .port(serverPort)
                .workflows(workflows)
                .build();
    }

    WorkflowRegistrationRequest.WorkflowInfo toWorkflowInfo(
            final WorkflowDefinition definition) {
        List<WorkflowRegistrationRequest.StepInfo> steps = definition.getSteps().stream()
                .map(step -> WorkflowRegistrationRequest.StepInfo.builder()
                        .id(step.getId())
                        .label(step.getLabel())
                        .description(step.getDescription())
                        .skippable(step.isSkippable())
                        .continueOnFailure(step.isContinueOnFailure())
                        .timeoutMs(step.getTimeout() != null
                                ? step.getTimeout().toMillis() : null)
                        .build())
                .toList();

        return WorkflowRegistrationRequest.WorkflowInfo.builder()
                .topic(definition.getTopic())
                .description(definition.getDescription())
                .steps(steps)
                .partitions(definition.getPartitions())
                .replication(definition.getReplication())
                .timeoutMs(definition.getTimeout() != null
                        ? definition.getTimeout().toMillis() : null)
                .build();
    }

    private String resolveHost() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}

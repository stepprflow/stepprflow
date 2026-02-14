package io.github.stepprflow.core.service;

import io.github.stepprflow.core.broker.MessageBroker;
import io.github.stepprflow.core.model.WorkflowDefinition;
import io.github.stepprflow.core.model.WorkflowMessage;
import io.github.stepprflow.core.model.WorkflowStatus;
import io.github.stepprflow.core.security.SecurityContextPropagator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Implementation of WorkflowStarter.
 */
@Service
@Slf4j
public class WorkflowStarterImpl implements WorkflowStarter {

    /** The workflow registry. */
    private final WorkflowRegistry registry;

    /** The message broker. */
    private final MessageBroker messageBroker;

    /** The security context propagator. */
    private final SecurityContextPropagator securityContextPropagator;

    /** The service name. */
    private final String serviceName;

    /**
     * Constructs a new WorkflowStarterImpl.
     *
     * @param workflowRegistry the workflow registry
     * @param broker the message broker
     * @param propagator the security context propagator
     * @param appName the service name
     */
    public WorkflowStarterImpl(
            final WorkflowRegistry workflowRegistry,
            final MessageBroker broker,
            final SecurityContextPropagator propagator,
            @Value("${spring.application.name:unknown}") final String appName) {
        this.registry = workflowRegistry;
        this.messageBroker = broker;
        this.securityContextPropagator = propagator;
        this.serviceName = appName;
        log.info("WorkflowStarterImpl initialized with SecurityContextPropagator: {}",
                propagator.getClass().getName());
    }

    @Override
    public String start(final String topic, final Object payload) {
        return start(topic, payload, null);
    }

    @Override
    public String start(
            final String topic,
            final Object payload,
            final Map<String, Object> metadata) {
        WorkflowDefinition definition = registry.getDefinition(topic);

        String executionId = UUID.randomUUID().toString();

        // Capture security context from current thread
        String securityContext = securityContextPropagator.capture();
        log.debug("Captured security context: {}", securityContext != null ? "present" : "null");

        int totalSteps = 0;
        String firstStepLabel = null;
        if (definition != null) {
            totalSteps = definition.getTotalSteps();
            firstStepLabel = definition.getStep(1) != null
                    ? definition.getStep(1).getLabel() : null;
        } else {
            log.info("Workflow topic '{}' not found locally, forwarding to remote service", topic);
        }

        WorkflowMessage message = WorkflowMessage.builder()
                .executionId(executionId)
                .correlationId(UUID.randomUUID().toString())
                .topic(topic)
                .serviceName(serviceName)
                .currentStep(1)
                .totalSteps(totalSteps)
                .currentStepLabel(firstStepLabel)
                .status(WorkflowStatus.PENDING)
                .payload(payload)
                .payloadType(payload.getClass().getName())
                .securityContext(securityContext)
                .metadata(metadata)
                .build();

        log.info("Starting workflow: topic={}, serviceName={}, executionId={}, securityContext={}",
                 topic, serviceName, executionId, securityContext != null ? "present" : "null");
        messageBroker.send(topic, message);

        return executionId;
    }

    @Override
    public CompletableFuture<String> startAsync(
            final String topic,
            final Object payload) {
        return CompletableFuture.supplyAsync(() -> start(topic, payload));
    }

    @Override
    public WorkflowMessage startAndGetMessage(
            final String topic,
            final Object payload) {
        WorkflowDefinition definition = registry.getDefinition(topic);

        String executionId = UUID.randomUUID().toString();

        // Capture security context from current thread
        String securityContext = securityContextPropagator.capture();

        int totalSteps = 0;
        String firstStepLabel = null;
        if (definition != null) {
            totalSteps = definition.getTotalSteps();
            firstStepLabel = definition.getStep(1) != null
                    ? definition.getStep(1).getLabel() : null;
        } else {
            log.info("Workflow topic '{}' not found locally, forwarding to remote service", topic);
        }

        WorkflowMessage message = WorkflowMessage.builder()
                .executionId(executionId)
                .correlationId(UUID.randomUUID().toString())
                .topic(topic)
                .serviceName(serviceName)
                .currentStep(1)
                .totalSteps(totalSteps)
                .currentStepLabel(firstStepLabel)
                .status(WorkflowStatus.PENDING)
                .payload(payload)
                .payloadType(payload.getClass().getName())
                .securityContext(securityContext)
                .build();

        log.info("Starting workflow: topic={}, executionId={}", topic, executionId);
        messageBroker.send(topic, message);

        return message;
    }

    @Override
    public void resume(final String executionId, final Integer stepId) {
        log.info("Resume workflow is not yet implemented");
        throw new UnsupportedOperationException(
                "Resume is implemented in async-workflow-monitor");
    }

    @Override
    public void cancel(final String executionId) {
        log.info("Cancel workflow is not yet implemented");
        throw new UnsupportedOperationException(
                "Cancel is implemented in async-workflow-monitor");
    }

    @Override
    public String forward(final String topic, final Object payload) {
        return forward(topic, payload, null);
    }

    @Override
    public String forward(
            final String topic,
            final Object payload,
            final Map<String, Object> metadata) {
        String executionId = UUID.randomUUID().toString();

        // Capture security context from current thread
        String securityContext = securityContextPropagator.capture();

        WorkflowMessage message = WorkflowMessage.builder()
                .executionId(executionId)
                .correlationId(UUID.randomUUID().toString())
                .topic(topic)
                .serviceName(serviceName)
                .currentStep(1)
                .totalSteps(0) // Unknown for remote workflows
                .status(WorkflowStatus.PENDING)
                .payload(payload)
                .payloadType(payload.getClass().getName())
                .securityContext(securityContext)
                .metadata(metadata)
                .build();

        log.info("Forwarding to remote workflow: topic={}, serviceName={}, executionId={}",
                 topic, serviceName, executionId);
        messageBroker.send(topic, message);

        return executionId;
    }
}

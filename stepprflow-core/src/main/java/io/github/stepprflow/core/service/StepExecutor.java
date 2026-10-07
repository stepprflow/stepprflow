package io.github.stepprflow.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stepprflow.core.StepprFlowProperties;
import io.github.stepprflow.core.broker.MessageBroker;
import io.github.stepprflow.core.model.ErrorInfo;
import io.github.stepprflow.core.model.RetryInfo;
import io.github.stepprflow.core.model.StepDefinition;
import io.github.stepprflow.core.model.WorkflowDefinition;
import io.github.stepprflow.core.model.WorkflowMessage;
import io.github.stepprflow.core.model.WorkflowStatus;
import io.github.stepprflow.core.security.ForgedSecurityContextException;
import io.github.stepprflow.core.security.SecurityContextPropagator;
import io.github.stepprflow.core.security.SecurityContextSigner;
import io.github.stepprflow.core.security.TrustedClassResolver;
import io.github.stepprflow.core.security.UntrustedPayloadTypeException;
import io.github.stepprflow.core.util.StackTraceUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Executes workflow steps.
 */
@Component
@Slf4j
public class StepExecutor {

    /** The workflow registry. */
    private final WorkflowRegistry registry;

    /** The message broker. */
    private final MessageBroker messageBroker;

    /** The stepprflow properties. */
    private final StepprFlowProperties properties;

    /** The JSON object mapper (configured for lenient deserialization). */
    private final ObjectMapper objectMapper;

    /** The backoff calculator for retry delays. */
    private final BackoffCalculator backoffCalculator;

    /** The callback method invoker. */
    private final CallbackMethodInvoker callbackMethodInvoker;

    /** The security context propagator. */
    private final SecurityContextPropagator securityContextPropagator;

    /** Resolves a payloadType to a class only if its package is trusted. */
    private final TrustedClassResolver trustedClassResolver;

    /** Verifies the integrity of a propagated security context. */
    private final SecurityContextSigner securityContextSigner;

    /**
     * Constructor with qualified ObjectMapper.
     *
     * @param registry the workflow registry
     * @param messageBroker the message broker
     * @param properties the stepprflow properties
     * @param objectMapper the stepprflow object mapper
     * @param backoffCalculator the backoff calculator
     * @param callbackMethodInvoker the callback method invoker
     * @param securityContextPropagator the security context propagator
     * @param trustedClassResolver resolves a payloadType only if its package is trusted
     * @param securityContextSigner verifies the integrity of a propagated security context
     */
    public StepExecutor(
            final WorkflowRegistry registry,
            final MessageBroker messageBroker,
            final StepprFlowProperties properties,
            @Qualifier("stepprflowObjectMapper") final ObjectMapper objectMapper,
            final BackoffCalculator backoffCalculator,
            final CallbackMethodInvoker callbackMethodInvoker,
            final SecurityContextPropagator securityContextPropagator,
            final TrustedClassResolver trustedClassResolver,
            final SecurityContextSigner securityContextSigner) {
        this.registry = registry;
        this.messageBroker = messageBroker;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.backoffCalculator = backoffCalculator;
        this.callbackMethodInvoker = callbackMethodInvoker;
        this.securityContextPropagator = securityContextPropagator;
        this.trustedClassResolver = trustedClassResolver;
        this.securityContextSigner = securityContextSigner;
    }

    /**
     * Execute a workflow step.
     *
     * @param message the workflow message
     */
    public void execute(final WorkflowMessage message) {
        String topic = message.getTopic();
        int stepId = message.getCurrentStep();

        WorkflowDefinition definition = registry.getDefinition(topic);
        if (definition == null) {
            log.error("Unknown workflow topic: {}", topic);
            return;
        }

        StepDefinition step = definition.getStep(stepId);
        if (step == null) {
            log.error("Unknown step {} for workflow {}", stepId, topic);
            return;
        }

        log.info("Executing step {}/{} ({}) for workflow {} [{}]",
                stepId, message.getTotalSteps(), step.getLabel(),
                topic, message.getExecutionId());

        // Set the current step label on the message for monitoring
        message.setCurrentStepLabel(step.getLabel());

        try {
            // Restore security context if present (SF-1: inside the try so that
            // a restore failure is handled, not propagated, and clear() in the
            // finally always runs — otherwise a throwing restore() escapes
            // execute(), leaks the context onto the pooled consumer thread, and
            // poison-pills the broker with endless redelivery).
            String securityContext = message.getSecurityContext();
            log.debug("Security context in message: {}, propagator: {}",
                    securityContext != null ? "present" : "NULL",
                    securityContextPropagator.getClass().getSimpleName());
            if (securityContext != null) {
                String rawContext;
                try {
                    // SF-3: verify the HMAC envelope (bound to executionId+topic)
                    // before restoring. A forged/tampered/replayed context is
                    // rejected here and never reaches the propagator.
                    rawContext = securityContextSigner.unwrapAndVerify(
                            message.getExecutionId(), topic, securityContext);
                } catch (Exception verifyError) {
                    handleRestoreFailure(message, step, definition, verifyError);
                    return;
                }
                try {
                    securityContextPropagator.restore(rawContext);
                } catch (Exception restoreError) {
                    // A restore failure (e.g. an expired/invalid propagated
                    // credential) will not succeed on redelivery — the credential
                    // is embedded in the message. Treat it as terminal: DLQ, never
                    // retry. Returning here still runs the finally (clear()).
                    handleRestoreFailure(message, step, definition, restoreError);
                    return;
                }
            }

            // Deserialize payload
            Object payload = deserializePayload(message, step);

            // Execute step method
            Method method = step.getMethod();
            method.invoke(definition.getHandler(), payload);

            // Check if last step
            if (definition.isLastStep(stepId)) {
                handleCompletion(message, definition, payload);
            } else {
                WorkflowMessage nextMessage = message.nextStepWithPayload(payload);
                // Look up the next step's label
                StepDefinition nextStep = definition.getStep(nextMessage.getCurrentStep());
                if (nextStep != null) {
                    nextMessage.setCurrentStepLabel(nextStep.getLabel());
                }
                // SF-4: confirmed send. All workflow-critical production
                // (advance/retry/complete/DLQ) uses sendSync so a broker failure
                // throws out of execute() and the consumed message is NOT acked
                // (it gets redelivered) instead of being silently lost. The
                // listener acks only after this returns, i.e. after the produced
                // message is durably persisted.
                messageBroker.sendSync(topic, nextMessage);
                log.info("Advanced to step {}/{} for workflow {} [{}]",
                        nextMessage.getCurrentStep(), message.getTotalSteps(),
                        topic, message.getExecutionId());
            }

        } catch (Exception e) {
            handleFailure(message, step, definition, e);
        } finally {
            // Always clear security context after execution. Guard it: if clear()
            // itself threw, the exception would escape execute() and re-introduce
            // the very poison-pill this method hardens against.
            try {
                securityContextPropagator.clear();
            } catch (Exception clearError) {
                log.error("Failed to clear security context after step for workflow {} [{}]",
                        message.getTopic(), message.getExecutionId(), clearError);
            }
        }
    }

    private Object deserializePayload(
            final WorkflowMessage message,
            final StepDefinition step) throws Exception {
        if (message.getPayload() == null) {
            return null;
        }

        String payloadType = message.getPayloadType();
        if (Objects.nonNull(payloadType)) {
            try {
                // SECURITY: rejects an untrusted payloadType (RCE guard) before
                // any class is loaded; UntrustedPayloadTypeException propagates
                // out and is treated as a non-retryable failure (DLQ).
                Class<?> payloadClass =
                        trustedClassResolver.loadTrustedClass(payloadType);
                return objectMapper.convertValue(message.getPayload(), payloadClass);
            } catch (ClassNotFoundException e) {
                log.warn("Could not find payload class {}, falling back to step parameter type",
                         payloadType);
                // Fall back to the step method's parameter type
                Class<?>[] paramTypes = step.getMethod().getParameterTypes();
                if (paramTypes.length == 1) {
                    return objectMapper.convertValue(message.getPayload(), paramTypes[0]);
                }
            }
        }

        return message.getPayload();
    }

    private void handleCompletion(
            final WorkflowMessage message,
            final WorkflowDefinition definition,
            final Object updatedPayload) {
        log.info("Workflow {} completed successfully [{}]",
                 message.getTopic(), message.getExecutionId());

        // Create message with updated payload for callback and completion
        WorkflowMessage messageWithPayload = message.toBuilder()
                .payload(updatedPayload)
                .build();

        // Call success callback if defined
        if (definition.getOnSuccessMethod() != null) {
            try {
                callbackMethodInvoker.invokeRaw(definition.getOnSuccessMethod(),
                              definition.getHandler(), messageWithPayload, null);
            } catch (Exception e) {
                log.error("Error in success callback", e);
            }
        }

        // Send completion message with updated payload
        WorkflowMessage completedMessage = messageWithPayload.complete();
        messageBroker.sendSync(message.getTopic() + ".completed", completedMessage);
    }

    private void handleFailure(
            final WorkflowMessage message,
            final StepDefinition step,
            final WorkflowDefinition definition,
            final Exception e) {
        Throwable cause = e instanceof InvocationTargetException
                ? e.getCause() : e;
        String errorMessage = cause.getMessage();

        log.error("Step {}/{} ({}) failed for workflow {} [{}]: {}",
                step.getId(), message.getTotalSteps(), step.getLabel(),
                message.getTopic(), message.getExecutionId(), errorMessage, cause);

        // Check if should continue on failure
        if (step.isContinueOnFailure() && !definition.isLastStep(step.getId())) {
            log.info("Continuing to next step despite failure (continueOnFailure=true)");
            WorkflowMessage nextMessage = message.nextStep();
            messageBroker.sendSync(message.getTopic(), nextMessage);
            return;
        }

        // Check if should retry
        RetryInfo retryInfo = message.getRetryInfo();
        if (retryInfo == null) {
            retryInfo = RetryInfo.builder()
                    .attempt(1)
                    .maxAttempts(properties.getRetry().getMaxAttempts())
                    .build();
        }

        if (!retryInfo.isExhausted() && isRetryable(cause)) {
            scheduleRetry(message, retryInfo, errorMessage);
        } else {
            // Send to DLQ
            sendToDlq(message, step, cause, "STEP_EXECUTION_FAILED");

            // Call failure callback
            if (definition.getOnFailureMethod() != null) {
                try {
                    callbackMethodInvoker.invokeRaw(definition.getOnFailureMethod(),
                                  definition.getHandler(), message, cause);
                } catch (Exception ex) {
                    log.error("Error in failure callback", ex);
                }
            }
        }
    }

    /**
     * Handle a security-context restore failure as a terminal, non-retryable
     * error: route the message straight to the DLQ and fire the failure
     * callback, bypassing the retry path.
     *
     * <p>A restore failure means the credential propagated inside the message
     * cannot be re-established (typically expired/invalid). Redelivering the
     * same message will fail identically, so retrying is pointless and would
     * poison-pill the broker; the step must never execute without its intended
     * security context.</p>
     *
     * @param message the workflow message
     * @param step the step being executed
     * @param definition the workflow definition
     * @param cause the exception thrown by the propagator's restore()
     */
    private void handleRestoreFailure(
            final WorkflowMessage message,
            final StepDefinition step,
            final WorkflowDefinition definition,
            final Throwable cause) {
        log.error("Security context restore failed for workflow {} [{}] at step {}/{} ({}); "
                        + "handling as terminal, non-retryable failure (DLQ if enabled): {}",
                message.getTopic(), message.getExecutionId(), step.getId(),
                message.getTotalSteps(), step.getLabel(), cause.getMessage(), cause);

        sendToDlq(message, step, cause, "SECURITY_CONTEXT_RESTORE_FAILED");

        if (definition.getOnFailureMethod() != null) {
            try {
                callbackMethodInvoker.invokeRaw(definition.getOnFailureMethod(),
                              definition.getHandler(), message, cause);
            } catch (Exception ex) {
                log.error("Error in failure callback", ex);
            }
        }
    }

    private boolean isRetryable(final Throwable cause) {
        // A rejected (untrusted) payloadType or a forged/tampered security
        // context will fail identically on redelivery and must never be
        // retried — route straight to the DLQ.
        if (cause instanceof UntrustedPayloadTypeException
                || cause instanceof ForgedSecurityContextException) {
            return false;
        }
        String exceptionType = cause.getClass().getName();
        return !properties.getRetry().getNonRetryableExceptions().contains(exceptionType);
    }

    private void scheduleRetry(
            final WorkflowMessage message,
            final RetryInfo retryInfo,
            final String errorMessage) {
        Duration delay = backoffCalculator.calculate(retryInfo.getAttempt());
        Instant nextRetry = Instant.now().plus(delay);

        RetryInfo newRetryInfo = retryInfo.nextAttempt(nextRetry, errorMessage);

        WorkflowMessage retryMessage = WorkflowMessage.builder()
                .executionId(message.getExecutionId())
                .correlationId(message.getCorrelationId())
                .topic(message.getTopic())
                .currentStep(message.getCurrentStep())
                .totalSteps(message.getTotalSteps())
                .currentStepLabel(message.getCurrentStepLabel())
                .status(WorkflowStatus.RETRY_PENDING)
                .payload(message.getPayload())
                .payloadType(message.getPayloadType())
                .securityContext(message.getSecurityContext())
                .metadata(message.getMetadata())
                .retryInfo(newRetryInfo)
                .createdAt(message.getCreatedAt())
                .updatedAt(Instant.now())
                .build();

        log.info("Scheduling retry {}/{} for workflow {} [{}] at {}",
                newRetryInfo.getAttempt(), newRetryInfo.getMaxAttempts(),
                message.getTopic(), message.getExecutionId(), nextRetry);

        // In core module, we just send to retry topic
        // The monitor module handles the scheduled retry
        messageBroker.sendSync(message.getTopic() + ".retry", retryMessage);
    }

    private void sendToDlq(
            final WorkflowMessage message,
            final StepDefinition step,
            final Throwable cause,
            final String errorCode) {
        if (!properties.getDlq().isEnabled()) {
            return;
        }

        ErrorInfo errorInfo = ErrorInfo.builder()
                .code(errorCode)
                .message(cause.getMessage())
                .exceptionType(cause.getClass().getName())
                .stackTrace(StackTraceUtils.truncate(cause))
                .stepId(step.getId())
                .stepLabel(step.getLabel())
                .build();

        WorkflowMessage dlqMessage = WorkflowMessage.builder()
                .executionId(message.getExecutionId())
                .correlationId(message.getCorrelationId())
                .topic(message.getTopic())
                .currentStep(message.getCurrentStep())
                .totalSteps(message.getTotalSteps())
                .currentStepLabel(message.getCurrentStepLabel())
                .status(WorkflowStatus.FAILED)
                .payload(message.getPayload())
                .payloadType(message.getPayloadType())
                .securityContext(message.getSecurityContext())
                .metadata(message.getMetadata())
                .retryInfo(message.getRetryInfo())
                .errorInfo(errorInfo)
                .createdAt(message.getCreatedAt())
                .updatedAt(Instant.now())
                .build();

        String dlqTopic = message.getTopic() + properties.getDlq().getSuffix();
        messageBroker.sendSync(dlqTopic, dlqMessage);

        log.info("Sent workflow {} [{}] to DLQ: {}",
                 message.getTopic(), message.getExecutionId(), dlqTopic);
    }
}

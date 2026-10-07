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
import io.github.stepprflow.core.exception.StepTimeoutException;
import io.github.stepprflow.core.idempotency.IdempotencyKey;
import io.github.stepprflow.core.idempotency.IdempotencyStore;
import io.github.stepprflow.core.security.UntrustedPayloadTypeException;
import io.github.stepprflow.core.util.StackTraceUtils;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

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
     * SF-6: bounded worker pool used to run steps under a timeout. Non-null
     * only when {@code stepprflow.timeout.enabled=true}; when null, timeout
     * enforcement is off and steps run inline on the consumer thread exactly
     * as before.
     */
    private final ExecutorService stepTimeoutExecutor;

    /**
     * SF-8: best-effort de-duplication store. May be null (feature off / no
     * bean), in which case no de-duplication is performed.
     */
    private final IdempotencyStore idempotencyStore;

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
     * @param idempotencyStore the de-duplication store, or null when disabled
     */
    @Autowired
    public StepExecutor(
            final WorkflowRegistry registry,
            final MessageBroker messageBroker,
            final StepprFlowProperties properties,
            @Qualifier("stepprflowObjectMapper") final ObjectMapper objectMapper,
            final BackoffCalculator backoffCalculator,
            final CallbackMethodInvoker callbackMethodInvoker,
            final SecurityContextPropagator securityContextPropagator,
            final TrustedClassResolver trustedClassResolver,
            final SecurityContextSigner securityContextSigner,
            final IdempotencyStore idempotencyStore) {
        this.registry = registry;
        this.messageBroker = messageBroker;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.backoffCalculator = backoffCalculator;
        this.callbackMethodInvoker = callbackMethodInvoker;
        this.securityContextPropagator = securityContextPropagator;
        this.trustedClassResolver = trustedClassResolver;
        this.securityContextSigner = securityContextSigner;
        this.idempotencyStore = idempotencyStore;
        // getTimeout() is always a populated Timeout on a real bean; tolerate a
        // null here only so a bare mocked properties doesn't NPE at construction.
        StepprFlowProperties.Timeout timeoutCfg = properties.getTimeout();
        this.stepTimeoutExecutor = (timeoutCfg != null && timeoutCfg.isEnabled())
                ? createTimeoutExecutor(timeoutCfg.getPoolSize())
                : null;
    }

    /**
     * Backwards-compatible constructor without a de-duplication store (SF-8
     * disabled). Kept so existing callers and tests compile unchanged.
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
        this(registry, messageBroker, properties, objectMapper, backoffCalculator,
                callbackMethodInvoker, securityContextPropagator, trustedClassResolver,
                securityContextSigner, null);
    }

    private static ExecutorService createTimeoutExecutor(final int poolSize) {
        final int size = poolSize > 0 ? poolSize : 1;
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(final Runnable r) {
                Thread t = new Thread(r, "stepprflow-timeout-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newFixedThreadPool(size, factory);
    }

    /**
     * SF-6: shut the timeout worker pool down on bean destruction so a
     * redeploy/restart does not leak threads.
     */
    @PreDestroy
    void shutdownTimeoutExecutor() {
        if (stepTimeoutExecutor != null) {
            stepTimeoutExecutor.shutdownNow();
        }
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

        // SF-8: skip a step already recorded as processed (a broker redelivery
        // of a step that already succeeded). Returning here lets the listener
        // acknowledge the duplicate and drop it. The check is fail-open: if the
        // store errors we process the message rather than risk dropping it.
        IdempotencyKey idempotencyKey = new IdempotencyKey(message.getExecutionId(), stepId);
        if (idempotencyActive()) {
            try {
                if (idempotencyStore.isProcessed(idempotencyKey)) {
                    log.info("Skipping already-processed step {} for workflow {} [{}]",
                            stepId, topic, message.getExecutionId());
                    return;
                }
            } catch (Exception storeError) {
                log.warn("Idempotency check failed for workflow {} [{}] step {}; processing anyway",
                        topic, message.getExecutionId(), stepId, storeError);
            }
        }

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
            String rawContext = null;
            if (securityContext != null) {
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

            // Execute step method. SF-6: when timeout enforcement is on and the
            // step has an effective timeout, run it on a bounded worker and
            // abort it past the deadline; otherwise invoke inline on the
            // consumer thread exactly as before.
            Method method = step.getMethod();
            Duration effectiveTimeout = resolveEffectiveTimeout(step);
            if (effectiveTimeout != null) {
                invokeWithTimeout(definition.getHandler(), method, payload,
                        rawContext, step, effectiveTimeout);
            } else {
                method.invoke(definition.getHandler(), payload);
            }

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

            // SF-8: record the step as processed only now that it has succeeded
            // AND its next message is durably produced. A record failure must not
            // trigger a retry (the advance already happened) — log and continue;
            // the residual cost is that a later redelivery may re-run this step.
            if (idempotencyActive()) {
                try {
                    idempotencyStore.markProcessed(idempotencyKey);
                } catch (Exception storeError) {
                    log.warn("Failed to record idempotency for workflow {} [{}] step {}",
                            topic, message.getExecutionId(), stepId, storeError);
                }
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

    /**
     * SF-8: whether de-duplication is active — a store is wired and the feature
     * is enabled. getIdempotency() is tolerated null only for a bare mocked
     * properties in tests (always populated on a real bean).
     *
     * @return true if the idempotency store should be consulted
     */
    private boolean idempotencyActive() {
        if (idempotencyStore == null) {
            return false;
        }
        StepprFlowProperties.Idempotency cfg = properties.getIdempotency();
        return cfg != null && cfg.isEnabled();
    }

    /**
     * SF-6: resolve the timeout to enforce for a step, or {@code null} when no
     * enforcement applies. Returns {@code null} when the feature is off (no
     * worker pool), or when neither the step's {@code @Timeout} nor the
     * configured {@code defaultStepTimeout} yields a positive duration.
     *
     * @param step the step definition
     * @return the effective timeout to enforce, or {@code null}
     */
    private Duration resolveEffectiveTimeout(final StepDefinition step) {
        if (stepTimeoutExecutor == null) {
            return null;
        }
        Duration timeout = step.getTimeout();
        if (timeout == null) {
            timeout = properties.getTimeout().getDefaultStepTimeout();
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return null;
        }
        return timeout;
    }

    /**
     * SF-6: run a step on the bounded worker pool, aborting it once the
     * deadline passes. The verified security context is re-restored on the
     * worker (the consumer thread already validated it, so this restore of the
     * same value is expected to succeed) and cleared in a finally on the
     * worker, mirroring the SF-1 hardening on the consumer thread so a timed-out
     * or interrupted step never leaks its context onto the pooled worker.
     *
     * <p>Cancellation is cooperative: {@code future.cancel(true)} interrupts the
     * worker, but a step that ignores interruption keeps running (holding its
     * worker and its context) until it returns.</p>
     *
     * @param handler the workflow handler instance
     * @param method the step method
     * @param payload the deserialized payload
     * @param rawContext the verified raw security context, or {@code null}
     * @param step the step definition (for the timeout exception)
     * @param timeout the effective timeout to enforce
     * @throws Exception the step's own exception (wrapped in
     *     {@link InvocationTargetException}), a {@link StepTimeoutException} on
     *     deadline, or an {@link InterruptedException} if the consumer thread is
     *     interrupted while waiting
     */
    private void invokeWithTimeout(
            final Object handler,
            final Method method,
            final Object payload,
            final String rawContext,
            final StepDefinition step,
            final Duration timeout) throws Exception {
        Future<?> future = stepTimeoutExecutor.submit(() -> {
            try {
                if (rawContext != null) {
                    securityContextPropagator.restore(rawContext);
                }
                method.invoke(handler, payload);
                return null;
            } finally {
                // SF-1/SF-6: clear UNCONDITIONALLY on the pooled worker (as the
                // consumer thread's finally does), so even a partial or failed
                // restore cannot leave a context on a reused worker. Clearing an
                // unset context is a no-op; guard against clear() itself throwing.
                try {
                    securityContextPropagator.clear();
                } catch (Exception clearError) {
                    log.error("Failed to clear security context on timeout worker "
                            + "for step {} ({})", step.getId(), step.getLabel(), clearError);
                }
            }
        });

        Instant start = Instant.now();
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            Duration elapsed = Duration.between(start, Instant.now());
            throw new StepTimeoutException(step.getLabel(), step.getId(), timeout, elapsed);
        } catch (ExecutionException ee) {
            // Unwrap and rethrow the worker's failure so handleFailure sees the
            // real cause (InvocationTargetException is unwrapped there as usual).
            Throwable cause = ee.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new IllegalStateException("Step failed with a non-Exception throwable", cause);
        } catch (InterruptedException ie) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw ie;
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
        // SF-13: match the configured non-retryable types against the whole
        // exception hierarchy, not just the exact runtime class name, so that a
        // subclass of a configured non-retryable exception (e.g. a
        // NumberFormatException when IllegalArgumentException is listed) is also
        // routed straight to the DLQ instead of being retried.
        List<String> nonRetryable = properties.getRetry().getNonRetryableExceptions();
        if (nonRetryable != null && !nonRetryable.isEmpty()) {
            for (Class<?> type = cause.getClass();
                    type != null && type != Object.class;
                    type = type.getSuperclass()) {
                if (nonRetryable.contains(type.getName())) {
                    return false;
                }
            }
        }
        return true;
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

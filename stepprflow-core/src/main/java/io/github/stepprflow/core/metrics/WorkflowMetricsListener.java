package io.github.stepprflow.core.metrics;

import io.github.stepprflow.core.event.WorkflowMessageEvent;
import io.github.stepprflow.core.model.WorkflowMessage;
import io.github.stepprflow.core.model.WorkflowStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

/**
 * Listens for workflow events and records metrics.
 * Works with WorkflowMetrics to update in-memory metrics counters.
 * Bean is created by WorkflowMetricsAutoConfiguration when
 * MeterRegistry is available.
 */
@Slf4j
public class WorkflowMetricsListener {

    /**
     * SF-14: default upper bound on the number of in-flight start-times
     * tracked. These maps are keyed by executionId (and executionId:stepId),
     * and an entry is removed only when a matching terminal event arrives. A
     * workflow that never reaches a terminal state (abandoned, lost, or a step
     * whose completion event never fires) would otherwise leak its entry
     * forever. Capping the maps as LRU bounds the memory a side metrics
     * structure can ever hold; an evicted long-lived entry only degrades its
     * duration to zero, never the JVM's health.
     */
    private static final int DEFAULT_MAX_TRACKED = 10_000;

    /**
     * The workflow metrics instance.
     */
    private final WorkflowMetrics metrics;

    /**
     * Track workflow start times for duration calculation (bounded LRU).
     */
    private final Map<String, Instant> workflowStartTimes;

    /**
     * Track step start times for duration calculation (bounded LRU).
     */
    private final Map<String, Instant> stepStartTimes;

    /**
     * Creates a listener with the default tracking bound.
     *
     * @param workflowMetrics the workflow metrics instance
     */
    public WorkflowMetricsListener(final WorkflowMetrics workflowMetrics) {
        this(workflowMetrics, DEFAULT_MAX_TRACKED);
    }

    /**
     * Creates a listener with an explicit tracking bound (for testing).
     *
     * @param workflowMetrics the workflow metrics instance
     * @param maxTracked the maximum number of start-time entries kept per map
     */
    WorkflowMetricsListener(final WorkflowMetrics workflowMetrics, final int maxTracked) {
        this.metrics = workflowMetrics;
        this.workflowStartTimes = Collections.synchronizedMap(new BoundedLru<>(maxTracked));
        this.stepStartTimes = Collections.synchronizedMap(new BoundedLru<>(maxTracked));
    }

    /**
     * Access-ordered {@link LinkedHashMap} that evicts its eldest entry once it
     * exceeds a fixed capacity, giving an O(1) bounded LRU cache.
     *
     * @param <K> key type
     * @param <V> value type
     */
    static final class BoundedLru<K, V> extends LinkedHashMap<K, V> {
        private static final long serialVersionUID = 1L;
        /** Maximum number of entries retained. */
        private final int maxEntries;

        BoundedLru(final int capacity) {
            super(16, 0.75f, true);
            this.maxEntries = capacity > 0 ? capacity : 1;
        }

        @Override
        protected boolean removeEldestEntry(final Map.Entry<K, V> eldest) {
            return size() > maxEntries;
        }
    }

    /**
     * Handles workflow message events.
     *
     * @param event the workflow message event
     */
    @EventListener
    public void onWorkflowMessage(final WorkflowMessageEvent event) {
        WorkflowMessage message = event.getMessage();
        String topic = message.getTopic();
        String executionId = message.getExecutionId();
        WorkflowStatus status = message.getStatus();

        try {
            switch (status) {
                case PENDING -> handlePending(message);
                case IN_PROGRESS -> handleInProgress(message);
                case COMPLETED -> handleCompleted(message);
                case FAILED -> handleFailed(message);
                case CANCELLED -> handleCancelled(message);
                case RETRY_PENDING -> handleRetryPending(message);
                default -> log.debug("Ignoring status {} for metrics",
                        status);
            }
        } catch (Exception e) {
            log.warn("Error recording metrics for workflow {} [{}]: {}",
                    topic, executionId, e.getMessage());
        }
    }

    /**
     * Handles pending workflow status.
     *
     * @param message the workflow message
     */
    private void handlePending(final WorkflowMessage message) {
        String executionId = message.getExecutionId();
        workflowStartTimes.put(executionId, Instant.now());
        metrics.recordWorkflowStarted(message.getTopic(),
                message.getServiceName());
    }

    /**
     * Handles in-progress workflow status.
     *
     * @param message the workflow message
     */
    private void handleInProgress(final WorkflowMessage message) {
        String executionId = message.getExecutionId();
        String stepKey = executionId + ":" + message.getCurrentStep();
        stepStartTimes.put(stepKey, Instant.now());
    }

    /**
     * Handles completed workflow status.
     *
     * @param message the workflow message
     */
    private void handleCompleted(final WorkflowMessage message) {
        String executionId = message.getExecutionId();
        Instant startTime = workflowStartTimes.remove(executionId);
        Duration duration = startTime != null
                ? Duration.between(startTime, Instant.now())
                : Duration.ZERO;
        metrics.recordWorkflowCompleted(message.getTopic(),
                message.getServiceName(), duration);
    }

    /**
     * Handles failed workflow status.
     *
     * @param message the workflow message
     */
    private void handleFailed(final WorkflowMessage message) {
        String executionId = message.getExecutionId();
        Instant startTime = workflowStartTimes.remove(executionId);
        Duration duration = startTime != null
                ? Duration.between(startTime, Instant.now())
                : Duration.ZERO;
        metrics.recordWorkflowFailed(message.getTopic(),
                message.getServiceName(), duration);
        metrics.recordDlq(message.getTopic());
    }

    /**
     * Handles cancelled workflow status.
     *
     * @param message the workflow message
     */
    private void handleCancelled(final WorkflowMessage message) {
        String executionId = message.getExecutionId();
        workflowStartTimes.remove(executionId);
        metrics.recordWorkflowCancelled(message.getTopic(),
                message.getServiceName());
    }

    /**
     * Handles retry pending workflow status.
     *
     * @param message the workflow message
     */
    private void handleRetryPending(final WorkflowMessage message) {
        int attempt = message.getRetryInfo() != null
                ? message.getRetryInfo().getAttempt() : 1;
        metrics.recordRetry(message.getTopic(), attempt);
    }

    /**
     * Record step completion with duration.
     * Called directly from StepExecutor after successful step execution.
     *
     * @param topic the workflow topic
     * @param stepLabel the step label
     * @param executionId the execution ID
     * @param stepId the step ID
     */
    public void recordStepCompleted(final String topic,
                                    final String stepLabel,
                                    final String executionId,
                                    final int stepId) {
        String stepKey = executionId + ":" + stepId;
        Instant startTime = stepStartTimes.remove(stepKey);
        Duration duration = startTime != null
                ? Duration.between(startTime, Instant.now())
                : Duration.ZERO;
        metrics.recordStepExecuted(topic, stepLabel, duration);
    }

    /**
     * Record step failure.
     *
     * @param topic the workflow topic
     * @param stepLabel the step label
     * @param isTimeout whether the failure was due to timeout
     */
    public void recordStepFailed(final String topic,
                                 final String stepLabel,
                                 final boolean isTimeout) {
        if (isTimeout) {
            metrics.recordStepTimeout(topic, stepLabel);
        }
        metrics.recordStepFailed(topic, stepLabel);
    }
}

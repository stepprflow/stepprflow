package io.github.stepprflow.core.idempotency;

/**
 * Identifies a single step execution for de-duplication (SF-8).
 *
 * <p>The key is {@code (executionId, step)}: the executionId is stable across a
 * workflow's step transitions and retries, and the step number isolates each
 * step. A broker redelivery of an already-processed step carries the same key;
 * a retry only occurs after a failure (which is never recorded as processed),
 * so it is not suppressed.
 *
 * @param executionId the workflow execution id
 * @param step the 1-based step number
 */
public record IdempotencyKey(String executionId, int step) {

    /**
     * A stable string form suitable for use as a store key.
     *
     * @return {@code executionId + ":" + step}
     */
    public String asString() {
        return executionId + ":" + step;
    }
}

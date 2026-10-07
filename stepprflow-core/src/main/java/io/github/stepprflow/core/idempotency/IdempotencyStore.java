package io.github.stepprflow.core.idempotency;

/**
 * SPI for best-effort de-duplication of step executions (SF-8).
 *
 * <p>stepprflow delivers at-least-once (see ADR-0002), so a step handler can run
 * more than once on redelivery. When idempotency is enabled, the executor checks
 * {@link #isProcessed(IdempotencyKey)} before running a step and calls
 * {@link #markProcessed(IdempotencyKey)} only after the step has succeeded and
 * its next message has been durably produced.
 *
 * <p>This is <strong>effectively-once, best-effort</strong>, not exactly-once:
 * a crash between producing the next message and recording the key, or two
 * in-flight consumers of the same key, can still cause a second execution.
 * Eliminating that entirely requires a transactional outbox, which is out of
 * scope.
 *
 * <p>core ships a bounded in-memory implementation; the
 * {@code stepprflow-idempotency-redis} module provides a distributed one.
 */
public interface IdempotencyStore {

    /**
     * Whether a completed execution has already been recorded for this key.
     *
     * @param key the step execution key
     * @return {@code true} if the step was already processed and should be skipped
     */
    boolean isProcessed(IdempotencyKey key);

    /**
     * Record that the step for this key has been processed successfully. Called
     * after the step succeeds and its next message is durably produced.
     *
     * @param key the step execution key
     */
    void markProcessed(IdempotencyKey key);
}

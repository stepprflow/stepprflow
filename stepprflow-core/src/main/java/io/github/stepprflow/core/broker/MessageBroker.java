package io.github.stepprflow.core.broker;

import io.github.stepprflow.core.model.WorkflowMessage;

import java.util.concurrent.CompletableFuture;

/**
 * Abstraction for message broker operations.
 * Implementations can be Kafka, RabbitMQ, or any other messaging system.
 *
 * <h2>Contract (SF-18)</h2>
 * stepprflow's at-least-once guarantee (ADR-0002) depends on the delivery
 * semantics below; an implementer MUST honor them, not just satisfy the
 * signatures:
 * <ul>
 *   <li><b>{@link #sendSync}</b> MUST block until the message is <em>durably
 *       accepted</em> by the broker (persisted/replicated per the broker's
 *       durability settings) and MUST throw if that cannot be confirmed. It MUST
 *       NOT return on a fire-and-forget basis. The executor relies on this to
 *       acknowledge a consumed record only after the produced message is safe;
 *       a {@code sendSync} that returns early reintroduces silent message loss.</li>
 *   <li><b>{@link #send}</b> / <b>{@link #sendAsync}</b> are best-effort/async and
 *       MAY return before durable acceptance; failures surface asynchronously.
 *       They MUST NOT be used on the workflow-critical path (advance / retry /
 *       complete / DLQ), which uses {@code sendSync}.</li>
 *   <li>All send methods MUST be safe to call repeatedly: the engine is
 *       at-least-once, so the same logical message may be produced more than once.</li>
 * </ul>
 */
public interface MessageBroker {

    /**
     * Send a message to a destination (topic/queue) asynchronously (best-effort).
     * MAY return before the broker durably accepts the message; failures surface
     * asynchronously. Not for the workflow-critical path — see {@link #sendSync}.
     *
     * @param destination the destination name
     * @param message     the workflow message to send
     */
    void send(String destination, WorkflowMessage message);

    /**
     * Send a message and return a future for async handling. The future
     * completes when the broker durably accepts the message, or completes
     * exceptionally on failure.
     *
     * @param destination the destination name
     * @param message     the workflow message to send
     * @return a future that completes when the message is durably accepted
     */
    CompletableFuture<Void> sendAsync(String destination, WorkflowMessage message);

    /**
     * Send a message synchronously, blocking until the broker has
     * <em>durably accepted</em> it, and throwing if that cannot be confirmed.
     * This is the workflow-critical send: see the contract on the type.
     *
     * @param destination the destination name
     * @param message     the workflow message to send
     * @throws RuntimeException if the message cannot be durably sent
     */
    void sendSync(String destination, WorkflowMessage message);

    /**
     * Get the broker type identifier.
     *
     * @return the broker type (e.g., "kafka", "rabbitmq")
     */
    String getBrokerType();

    /**
     * Check if the broker is available and connected.
     *
     * @return true if the broker is ready
     */
    default boolean isAvailable() {
        return true;
    }
}

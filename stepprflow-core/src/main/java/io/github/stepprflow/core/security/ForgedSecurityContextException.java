package io.github.stepprflow.core.security;

/**
 * Thrown when a propagated security context fails HMAC verification.
 *
 * <p>The context travels inside the broker message and is therefore
 * attacker-controlled for anyone able to publish to the internal broker. When
 * context signing is enabled, a context whose signature is missing or does not
 * match (forgery, tampering, or replay onto a different execution/topic) is
 * rejected before it is restored, and the message is routed to the DLQ as a
 * terminal, non-retryable failure.</p>
 */
public class ForgedSecurityContextException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Create the exception for a rejected context.
     *
     * @param executionId the workflow execution the context was bound to
     * @param reason why verification failed
     */
    public ForgedSecurityContextException(
            final String executionId, final String reason) {
        super("Rejecting a propagated security context for workflow execution '"
                + executionId + "': " + reason + ". It was not produced by a "
                + "service holding the configured signing secret (possible "
                + "forgery, tampering, or replay).");
    }
}

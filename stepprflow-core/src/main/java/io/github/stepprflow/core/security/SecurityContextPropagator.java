package io.github.stepprflow.core.security;

/**
 * Interface for propagating security context across workflow steps.
 * <p>
 * Implementations are responsible for:
 * <ul>
 *   <li>Capturing the current security context (e.g., JWT token, authentication)</li>
 *   <li>Restoring the security context before step execution</li>
 *   <li>Clearing the security context after step execution</li>
 * </ul>
 * </p>
 *
 * <h2>Contract (SF-18)</h2>
 * <ul>
 *   <li>{@link #capture()} is invoked on the <em>caller's</em> thread when a
 *       workflow starts; it MUST read the caller's context there (it is never
 *       called on an engine worker thread).</li>
 *   <li>{@link #restore(String)} and {@link #clear()} bracket a single step
 *       execution on a possibly-pooled engine thread: the engine always calls
 *       {@code clear()} in a {@code finally}, and an implementation MUST keep
 *       the context thread-confined so a cleared context never leaks to the next
 *       task on the same pooled thread.</li>
 *   <li>Implementations MUST be thread-safe (steps run concurrently) and MUST
 *       tolerate a {@code null} context in {@code restore} as a no-op.</li>
 *   <li>Integrity (signing/verification) is handled by the engine around this
 *       SPI; an implementation only moves the opaque context string.</li>
 * </ul>
 *
 * @see NoOpSecurityContextPropagator
 */
public interface SecurityContextPropagator {

    /**
     * Capture the current security context.
     * <p>
     * This is typically called when starting a workflow to capture
     * the caller's authentication/authorization context.
     * </p>
     *
     * @return the serialized security context (e.g., JWT token), or null if none
     */
    String capture();

    /**
     * Restore the security context before executing a workflow step.
     * <p>
     * This should set up the security context so that the step method
     * can access the authenticated principal and authorities.
     * </p>
     *
     * @param securityContext the serialized security context to restore
     */
    void restore(String securityContext);

    /**
     * Clear the security context after step execution.
     * <p>
     * This should clean up any thread-local or context state
     * to prevent security context leakage.
     * </p>
     */
    void clear();

    /**
     * Check if security propagation is enabled.
     *
     * @return true if security context should be propagated
     */
    boolean isEnabled();
}

package io.github.stepprflow.core.security;

import java.util.List;

/**
 * Thrown when a workflow message declares a {@code payloadType} whose class is
 * not under any configured trusted package.
 *
 * <p>The {@code payloadType} travels inside the broker message and is therefore
 * attacker-controlled for anyone able to publish to the internal broker.
 * Resolving an arbitrary class via reflection and letting Jackson instantiate it
 * is a deserialization-gadget Remote Code Execution vector. This exception marks
 * such an attempt so the step is never executed and the message is routed to the
 * DLQ as a terminal, non-retryable failure (retrying would re-process the same
 * malicious payload identically).</p>
 */
public class UntrustedPayloadTypeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Create the exception for a rejected payload type.
     *
     * @param payloadType the untrusted fully-qualified class name from the message
     * @param trustedPackages the configured trusted packages it failed to match
     */
    public UntrustedPayloadTypeException(
            final String payloadType, final List<String> trustedPackages) {
        super("Refusing to deserialize untrusted payloadType '" + payloadType
                + "': its package is not in the configured trusted packages "
                + trustedPackages + ". If this type is legitimate, add its "
                + "package to stepprflow.trusted-packages.");
    }
}

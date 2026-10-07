package io.github.stepprflow.core.security;

import io.github.stepprflow.core.StepprFlowProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Signs and verifies the integrity of a propagated security context using an
 * HMAC bound to the workflow execution and topic.
 *
 * <p>The security context travels as a plain string inside the broker message,
 * so anyone able to publish to the internal broker could forge or replay one.
 * When a shared secret is configured ({@code stepprflow.security.context-signing.secret}),
 * the captured context is wrapped as {@code <signature>.<rawContext>} and the
 * signature is verified before the context is restored. Binding the HMAC to the
 * {@code executionId} and {@code topic} prevents replaying a valid context onto
 * a different workflow.</p>
 *
 * <p>When no secret is configured, signing is disabled and the context is passed
 * through unchanged (a one-time warning is logged the first time a context is
 * propagated) — this keeps the upgrade non-breaking until the secret is
 * distributed to every participating service.</p>
 */
@Component
@Slf4j
public class SecurityContextSigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final char DELIMITER = '.';

    /** The HMAC key, or null when signing is disabled. */
    private final byte[] secret;

    /** Whether a secret is configured. */
    private final boolean enabled;

    /** Guards the one-time "signing disabled" warning. */
    private final AtomicBoolean warned = new AtomicBoolean(false);

    /**
     * Build the signer from configuration.
     *
     * @param properties the stepprflow properties
     */
    public SecurityContextSigner(final StepprFlowProperties properties) {
        final String configured =
                properties.getSecurity().getContextSigning().getSecret();
        if (configured != null && !configured.isBlank()) {
            this.secret = configured.getBytes(StandardCharsets.UTF_8);
            this.enabled = true;
        } else {
            this.secret = null;
            this.enabled = false;
        }
    }

    /**
     * @return true if context signing is enabled (a secret is configured)
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Wrap a freshly captured context into a signed envelope.
     *
     * @param executionId the workflow execution id
     * @param topic the workflow topic
     * @param rawContext the captured security context (may be null)
     * @return the signed envelope, or {@code rawContext} unchanged when signing
     *         is disabled or the context is null
     */
    public String wrap(final String executionId, final String topic,
            final String rawContext) {
        if (!enabled || rawContext == null) {
            return rawContext;
        }
        return hmac(executionId, topic, rawContext) + DELIMITER + rawContext;
    }

    /**
     * Verify and unwrap a stored (possibly signed) context.
     *
     * @param executionId the workflow execution id the context must be bound to
     * @param topic the workflow topic the context must be bound to
     * @param storedContext the context as carried by the message (may be null)
     * @return the raw security context to restore (null if none)
     * @throws ForgedSecurityContextException if signing is enabled and the
     *         signature is missing or does not match
     */
    public String unwrapAndVerify(final String executionId, final String topic,
            final String storedContext) {
        if (storedContext == null) {
            return null;
        }
        if (!enabled) {
            if (warned.compareAndSet(false, true)) {
                log.warn("A security context is being propagated but "
                        + "stepprflow.security.context-signing.secret is not set: "
                        + "propagated contexts are NOT integrity-protected. "
                        + "Configure a shared secret to enable HMAC signing.");
            }
            return storedContext;
        }
        final int i = storedContext.indexOf(DELIMITER);
        if (i <= 0) {
            throw new ForgedSecurityContextException(executionId,
                    "missing signature");
        }
        final String provided = storedContext.substring(0, i);
        final String raw = storedContext.substring(i + 1);
        if (!constantTimeEquals(provided, hmac(executionId, topic, raw))) {
            throw new ForgedSecurityContextException(executionId,
                    "signature mismatch");
        }
        return raw;
    }

    private String hmac(final String executionId, final String topic,
            final String raw) {
        try {
            final Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            final String data = nullToEmpty(executionId) + ":"
                    + nullToEmpty(topic) + ":" + raw;
            final byte[] out = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) {
            // A broken MAC must fail loudly, never silently drop integrity.
            throw new IllegalStateException(
                    "Failed to compute security context signature", e);
        }
    }

    private static boolean constantTimeEquals(final String a, final String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private static String nullToEmpty(final String s) {
        return s == null ? "" : s;
    }
}

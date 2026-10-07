package io.github.stepprflow.core.security;

import io.github.stepprflow.core.StepprFlowProperties;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Resolves a message's {@code payloadType} to a {@link Class} only when its
 * package is explicitly trusted.
 *
 * <p>This is the application-layer guard against deserialization-gadget RCE: the
 * {@code payloadType} is attacker-controlled (it travels inside the broker
 * message), so a class is loaded via reflection only after its package has been
 * matched against the configured allow-list. An untrusted type is rejected with
 * {@link UntrustedPayloadTypeException} and never loaded.</p>
 *
 * <p>The effective allow-list is the union of the broker-agnostic
 * {@code stepprflow.trusted-packages} and the per-broker
 * {@code stepprflow.kafka.trusted-packages} / {@code stepprflow.rabbitmq.trusted-packages}
 * (the latter kept for backward compatibility). It is validated for shape
 * (no wildcards) via {@link TrustedPackagesValidator} at construction.</p>
 */
@Component
public class TrustedClassResolver {

    /**
     * Safe JDK baseline packages that are always trusted, so that simple scalar
     * and collection payloads (String, Integer, Map, dates, ...) keep working
     * without every application having to allow-list the JDK. These hold data
     * types that Jackson materializes from JSON; application and third-party
     * gadget classes stay blocked unless explicitly configured. Mirrors the
     * java.util/java.lang defaults of Spring's message type mappers.
     */
    private static final List<String> JDK_BASELINE =
            List.of("java.lang", "java.util", "java.time", "java.math");

    /** The validated, de-duplicated list of trusted packages. */
    private final List<String> trustedPackages;

    /**
     * Build the resolver from the configured properties.
     *
     * @param properties the stepprflow properties
     */
    public TrustedClassResolver(final StepprFlowProperties properties) {
        final Set<String> merged = new LinkedHashSet<>(JDK_BASELINE);
        addAll(merged, properties.getTrustedPackages());
        if (properties.getKafka() != null) {
            addAll(merged, properties.getKafka().getTrustedPackages());
        }
        if (properties.getRabbitmq() != null) {
            addAll(merged, properties.getRabbitmq().getTrustedPackages());
        }
        final List<String> effective = List.copyOf(merged);
        // Fail fast on a misconfigured (e.g. wildcard) allow-list at startup.
        TrustedPackagesValidator.validate(effective);
        this.trustedPackages = effective;
    }

    private static void addAll(final Set<String> target, final List<String> src) {
        if (src != null) {
            for (String pkg : src) {
                if (pkg != null && !pkg.isBlank()) {
                    target.add(pkg.trim());
                }
            }
        }
    }

    /**
     * Load the class named by {@code payloadType}, but only if its package is
     * trusted.
     *
     * @param payloadType the fully-qualified class name from the message
     * @return the resolved class
     * @throws UntrustedPayloadTypeException if the type's package is not trusted
     * @throws ClassNotFoundException if the (trusted) class cannot be found
     */
    public Class<?> loadTrustedClass(final String payloadType)
            throws ClassNotFoundException {
        if (!isTrusted(payloadType)) {
            throw new UntrustedPayloadTypeException(payloadType, trustedPackages);
        }
        return Class.forName(payloadType);
    }

    /**
     * Whether {@code payloadType} belongs to a trusted package.
     *
     * @param payloadType the fully-qualified class name from the message
     * @return true if the type is under a trusted package
     */
    public boolean isTrusted(final String payloadType) {
        if (payloadType == null || payloadType.isBlank()) {
            return false;
        }
        for (String pkg : trustedPackages) {
            // Exact match (unlikely for an FQCN) or a real sub-path: the trailing
            // dot prevents 'com.evil' from matching trusted 'com.ev'.
            if (payloadType.equals(pkg) || payloadType.startsWith(pkg + ".")) {
                return true;
            }
        }
        return false;
    }
}

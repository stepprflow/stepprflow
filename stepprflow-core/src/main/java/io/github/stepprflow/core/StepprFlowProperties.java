package io.github.stepprflow.core;

import java.time.Duration;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for StepprFlow.
 */
@ConfigurationProperties(prefix = "stepprflow")
@Data
public class StepprFlowProperties {

    /**
     * Enable/disable Steppr Flow workflow engine.
     */
    private boolean enabled = true;

    /**
     * Message broker type: kafka or rabbitmq.
     */
    private BrokerType broker = BrokerType.KAFKA;

    /**
     * Kafka configuration.
     */
    private Kafka kafka = new Kafka();

    /**
     * RabbitMQ configuration.
     */
    private RabbitMQ rabbitmq = new RabbitMQ();

    /**
     * Broker-agnostic trusted packages for JSON payload deserialization.
     * <p>
     * SECURITY: this is the canonical allow-list enforced at the application
     * layer before a message's {@code payloadType} is resolved via reflection
     * ({@code Class.forName}). A {@code payloadType} whose class is not under one
     * of these packages is rejected (never loaded) — this is the guard against
     * deserialization-gadget Remote Code Execution. Never use "*".
     * </p>
     * <p>
     * Add your application's payload packages here, e.g.
     * {@code ["io.github.stepprflow.core.model", "com.mycompany.workflow.payload"]}.
     * The per-broker {@code kafka.trusted-packages} / {@code rabbitmq.trusted-packages}
     * are still honored (unioned in) for backward compatibility, but this
     * broker-agnostic property is the recommended place to configure them.
     * </p>
     */
    private List<String> trustedPackages =
            List.of("io.github.stepprflow.core.model");

    /**
     * Retry configuration.
     */
    private Retry retry = new Retry();

    /**
     * Dead Letter Queue configuration.
     */
    private Dlq dlq = new Dlq();

    /**
     * Security configuration.
     */
    private Security security = new Security();

    /**
     * Circuit breaker configuration.
     */
    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    /**
     * Timeout configuration.
     */
    private Timeout timeout = new Timeout();

    /**
     * Idempotency / de-duplication configuration.
     */
    private Idempotency idempotency = new Idempotency();

    /**
     * Supported broker types.
     */
    public enum BrokerType {
        /**
         * Apache Kafka.
         */
        KAFKA,

        /**
         * RabbitMQ.
         */
        RABBITMQ
    }

    /**
     * Kafka-specific configuration.
     */
    @Data
    public static class Kafka {
        /**
         * Kafka bootstrap servers.
         */
        private String bootstrapServers = "localhost:9092";

        /**
         * Consumer configuration.
         */
        private Consumer consumer = new Consumer();

        /**
         * Producer configuration.
         */
        private Producer producer = new Producer();

        /**
         * Regex pattern for the Kafka topics this service listens to.
         * <p>
         * SECURITY/PERF: the default {@code ".*"} subscribes to EVERY topic on
         * the cluster. On a shared cluster this makes each service fetch and
         * deserialize all other services' messages (CPU, log noise); only the
         * runtime guard in the listener keeps them from being processed. Scope
         * this to your own workflow topics (and their {@code .retry}) in any
         * shared-cluster deployment — a startup warning is logged while it is
         * left at {@code ".*"}.
         * </p>
         */
        private String topicPattern = ".*";

        /**
         * Base workflow topics (without any stepprflow suffix) that a
         * monitoring instance should track.
         * <p>
         * When set and {@code topic-pattern} is left at its default, the
         * monitoring module derives the effective {@code topicPattern} itself
         * by expanding each base topic with every stepprflow suffix
         * ({@code .completed}, {@code .retry}, the configured
         * {@code dlq.suffix}, and Spring Kafka's {@code .dlt}) plus the
         * registration topic. This exists because manually scoping
         * {@code topic-pattern} is error-prone: forgetting a single suffix
         * (typically {@code .completed}) silently stops the monitor from ever
         * seeing workflow completions, leaving executions IN_PROGRESS
         * forever. An explicitly configured {@code topic-pattern} still takes
         * precedence over this property, for backward compatibility.
         * </p>
         * <p>
         * Ignored outside the monitoring module.
         * </p>
         */
        private List<String> workflowTopics = List.of();

        /**
         * Auto-create topics.
         */
        private boolean autoCreateTopics = true;

        /**
         * Trusted packages for JSON deserialization.
         * <p>
         * SECURITY: Never use "*" (wildcard) as it enables Remote Code
         * Execution attacks. Specify only the packages that contain your
         * workflow payload classes.
         * </p>
         * <p>
         * Default includes only the stepprflow core model package.
         * Add your application's payload packages here.
         * </p>
         * Example: ["io.github.stepprflow.core.model",
         * "com.mycompany.workflow.payload"]
         */
        private List<String> trustedPackages =
                List.of("io.github.stepprflow.core.model");

        /**
         * Kafka consumer configuration.
         */
        @Data
        public static class Consumer {
            /**
             * Consumer group ID.
             */
            private String groupId;

            /**
             * Auto offset reset strategy.
             */
            private String autoOffsetReset = "earliest";

            /**
             * Concurrency level.
             */
            private int concurrency = 1;

            /**
             * Poll timeout in milliseconds.
             */
            private int pollTimeout = 3000;
        }

        /**
         * Kafka producer configuration.
         */
        @Data
        public static class Producer {
            /**
             * Acknowledgment mode.
             */
            private String acks = "all";

            /**
             * Number of retries.
             */
            private int retries = 3;

            /**
             * Batch size.
             */
            private int batchSize = 16384;

            /**
             * Linger time in milliseconds.
             */
            private int lingerMs = 5;
        }
    }

    /**
     * Retry configuration.
     */
    @Data
    public static class Retry {
        /**
         * Maximum retry attempts.
         */
        private int maxAttempts = 3;

        /**
         * Initial delay before first retry.
         */
        private Duration initialDelay = Duration.ofSeconds(1);

        /**
         * Maximum delay between retries.
         */
        private Duration maxDelay = Duration.ofMinutes(5);

        /**
         * Backoff multiplier.
         */
        private double multiplier = 2.0;

        /**
         * Exceptions that should not be retried.
         */
        private List<String> nonRetryableExceptions = List.of(
                "java.lang.IllegalArgumentException"
        );
    }

    /**
     * Dead Letter Queue configuration.
     */
    @Data
    public static class Dlq {
        /**
         * Enable Dead Letter Queue.
         */
        private boolean enabled = true;

        /**
         * Suffix for DLQ topics.
         */
        private String suffix = ".dlq";
    }

    /**
     * Security configuration.
     */
    @Data
    public static class Security {
        /**
         * Propagate security context between steps.
         */
        private boolean propagateContext = true;

        /**
         * Header name for access token.
         */
        private String tokenHeader = "Authorization";

        /**
         * HMAC signing of the propagated security context.
         */
        private ContextSigning contextSigning = new ContextSigning();

        /**
         * Opt-in strict deserialization: when {@code true}, a message's
         * {@code payloadType} is resolved through the trusted-packages allowlist
         * ({@code Class.forName}) and an untrusted type is rejected (the 1.1.x
         * hardening behaviour).
         *
         * <p>Default {@code false}: the {@code payloadType} declared by the
         * sender is never instantiated. Instead the payload is deserialized into
         * the receiver's own {@code @Step}/callback parameter type (or left as a
         * raw map when the parameter is {@code Object}). This removes the
         * reflective-instantiation surface entirely and decouples services — a
         * consumer never needs the producer's classes on its classpath, nor any
         * {@code trusted-packages} configuration.</p>
         */
        private boolean trustedPackageEnforcement = false;
    }

    /**
     * HMAC signing of the propagated security context envelope.
     * <p>
     * When a {@code secret} is configured, stepprflow signs the captured
     * security context (bound to the workflow executionId and topic) and
     * verifies the signature before restoring it, rejecting any forged or
     * replayed context. When no secret is set, signing is disabled and the
     * context travels unprotected (a warning is emitted the first time one is
     * propagated) — set a secret to enable integrity protection.
     * </p>
     */
    @Data
    public static class ContextSigning {
        /**
         * Shared HMAC-SHA256 secret, distributed to every service that
         * participates in the same workflows. Blank/null disables signing.
         */
        private String secret;
    }

    /**
     * RabbitMQ-specific configuration.
     */
    @Data
    public static class RabbitMQ {
        /**
         * RabbitMQ host.
         */
        private String host = "localhost";

        /**
         * RabbitMQ port.
         */
        private int port = 5672;

        /**
         * RabbitMQ username.
         */
        private String username = "guest";

        /**
         * RabbitMQ password.
         */
        private String password = "guest";

        /**
         * Virtual host.
         */
        private String virtualHost = "/";

        /**
         * Exchange name for workflows.
         */
        private String exchange = "stepprflow.workflows";

        /**
         * Prefetch count for consumers.
         */
        private int prefetchCount = 10;

        /**
         * Suffix for DLQ queues.
         */
        private String dlqSuffix = ".dlq";

        /**
         * Trusted packages for JSON deserialization.
         * <p>
         * SECURITY: Never use "*" (wildcard) as it enables Remote Code
         * Execution attacks. Specify only the packages that contain your
         * workflow payload classes.
         * </p>
         */
        private List<String> trustedPackages =
                List.of("io.github.stepprflow.core.model");
    }

    /**
     * Circuit breaker configuration.
     */
    @Data
    public static class CircuitBreaker {
        /**
         * Enable circuit breaker.
         */
        private boolean enabled = true;

        /**
         * Failure rate threshold percentage to open the circuit.
         */
        private float failureRateThreshold = 50;

        /**
         * Slow call rate threshold percentage.
         */
        private float slowCallRateThreshold = 100;

        /**
         * Duration threshold for slow calls.
         */
        private Duration slowCallDurationThreshold = Duration.ofSeconds(60);

        /**
         * Sliding window size.
         */
        private int slidingWindowSize = 100;

        /**
         * Minimum number of calls before calculating failure rate.
         */
        private int minimumNumberOfCalls = 10;

        /**
         * Calls permitted in half-open state.
         */
        private int permittedNumberOfCallsInHalfOpenState = 10;

        /**
         * Wait duration in open state before transitioning to half-open.
         */
        private Duration waitDurationInOpenState = Duration.ofSeconds(60);

        /**
         * Automatically transition from open to half-open.
         */
        private boolean automaticTransitionFromOpenToHalfOpenEnabled = true;
    }

    /**
     * Timeout configuration.
     */
    @Data
    public static class Timeout {
        /**
         * Enable per-step timeout enforcement (SF-6).
         * <p>
         * When {@code false} (the default), a {@code @Timeout} on a step is
         * only exported as registration/monitoring metadata and is NOT
         * enforced at runtime — steps run unbounded. When {@code true}, each
         * step with an effective timeout (its {@code @Timeout}, else
         * {@link #defaultStepTimeout}) runs on a bounded worker pool and is
         * cancelled with a {@code StepTimeoutException} (retryable) once the
         * deadline passes. Cancellation is cooperative: a step that ignores
         * thread interruption (e.g. a tight CPU loop or an uninterruptible
         * native call) keeps running until it returns — design steps to honor
         * interruption.
         * </p>
         */
        private boolean enabled = false;

        /**
         * Default timeout applied to steps that declare no {@code @Timeout},
         * used only when {@link #enabled} is {@code true}. Set to {@code null}
         * to leave un-annotated steps unbounded even when enforcement is on.
         */
        private Duration defaultStepTimeout = Duration.ofMinutes(5);

        /**
         * Size of the bounded worker pool used to run steps under a timeout
         * (only created when {@link #enabled} is {@code true}). It caps the
         * number of steps that can run concurrently under enforcement; a step
         * stuck in uninterruptible code holds its worker until it returns, so
         * size this with the consumer concurrency in mind.
         */
        private int poolSize = 10;
    }

    /**
     * Best-effort step de-duplication (SF-8). Disabled by default: a step may
     * then run more than once on redelivery (at-least-once). When enabled, a
     * step already recorded as processed is skipped and acknowledged.
     */
    @Data
    public static class Idempotency {
        /**
         * Enable de-duplication. When {@code false} (default), no store is
         * consulted and steps run exactly as before.
         */
        private boolean enabled = false;

        /**
         * Which store backs de-duplication: {@code inmemory} (per-instance,
         * core default) or {@code redis} (distributed, provided by
         * {@code stepprflow-idempotency-redis}).
         */
        private String store = "inmemory";

        /**
         * How long a processed key is remembered. Must exceed the broker's
         * redelivery/retry window, otherwise a late redelivery is re-processed.
         */
        private Duration ttl = Duration.ofHours(24);

        /**
         * Maximum number of keys retained by the in-memory store (LRU beyond
         * this). Ignored by distributed stores.
         */
        private int inMemoryMaxSize = 100_000;

        /**
         * Key prefix used by distributed stores (e.g. Redis) to namespace keys.
         */
        private String keyPrefix = "stepprflow:idem:";
    }

}

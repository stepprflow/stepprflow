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
         * Pattern for topics to listen to.
         */
        private String topicPattern = ".*";

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
         * Enable timeout enforcement.
         */
        private boolean enabled = false;

        /**
         * Default timeout for steps without explicit timeout.
         */
        private Duration defaultStepTimeout = Duration.ofMinutes(5);
    }

}

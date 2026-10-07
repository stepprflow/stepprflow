package io.github.stepprflow.monitor;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "stepprflow.monitor")
@Data
public class MonitorProperties {

    /**
     * Enable monitoring module.
     */
    private boolean enabled = true;

    /**
     * MongoDB collection name for executions.
     */
    private String collectionName = "workflow_executions";

    /**
     * WebSocket configuration.
     */
    private WebSocket webSocket = new WebSocket();

    /**
     * Retention configuration.
     */
    private Retention retention = new Retention();

    /**
     * Retry scheduler configuration.
     */
    private RetryScheduler retryScheduler = new RetryScheduler();

    /**
     * Registry configuration for workflow registration from agents.
     */
    private Registry registry = new Registry();

    /**
     * Outbox configuration for reliable message delivery.
     */
    private Outbox outbox = new Outbox();

    /**
     * MongoDB configuration for workflow persistence.
     */
    private MongoDB mongodb = new MongoDB();

    /**
     * Authentication configuration for the monitoring API and dashboard (SF-5).
     */
    private Auth auth = new Auth();

    /**
     * Dual-mode authentication for the monitoring endpoints and UI.
     * <p>
     * {@code mode} selects {@code basic} (form/HTTP-Basic login against a
     * configured user) or {@code oidc} (server-side OAuth2 login against a
     * Keycloak/OIDC provider configured via the standard
     * {@code spring.security.oauth2.client.*} properties). If {@code mode} is
     * unset the security filter chain denies everything (fail-closed): the
     * monitoring endpoints are never exposed unauthenticated.
     * </p>
     */
    @Data
    public static class Auth {
        /** {@code basic} or {@code oidc}; null/blank denies all (fail-closed). */
        private String mode;

        /** Basic-mode (local user) configuration. */
        private Basic basic = new Basic();

        /** OIDC-mode role mapping configuration. */
        private Oidc oidc = new Oidc();
    }

    @Data
    public static class Basic {
        /** Local admin username. */
        private String username = "admin";

        /**
         * Local admin password. A bcrypt hash ({$2a$...}) is used as-is; any
         * other value is treated as a plaintext password (logged as a warning).
         * Required when {@code auth.mode=basic}.
         */
        private String password;

        /** Authority granted to the local user: {@code OPERATOR} or {@code VIEWER}. */
        private String role = "OPERATOR";
    }

    @Data
    public static class Oidc {
        /** Token claim holding the caller's role names. */
        private String rolesClaim = "roles";

        /** OIDC role name that maps to the OPERATOR authority. */
        private String operatorRole = "stepprflow-operator";

        /** OIDC role name that maps to the VIEWER authority. */
        private String viewerRole = "stepprflow-viewer";
    }

    @Data
    public static class WebSocket {
        private boolean enabled = true;
        private String endpoint = "/ws/workflow";
        private String topicPrefix = "/topic/workflow";
    }

    @Data
    public static class Retention {
        /**
         * How long to keep completed executions.
         */
        private Duration completedTtl = Duration.ofDays(7);

        /**
         * How long to keep failed executions.
         */
        private Duration failedTtl = Duration.ofDays(30);
    }

    @Data
    public static class RetryScheduler {
        /**
         * Enable automatic retry processing.
         */
        private boolean enabled = true;

        /**
         * Interval for checking pending retries.
         */
        private Duration checkInterval = Duration.ofSeconds(30);
    }

    @Data
    public static class Registry {
        /**
         * Timeout after which an instance without heartbeat is considered stale.
         * Stale instances are automatically removed during cleanup.
         */
        private Duration instanceTimeout = Duration.ofSeconds(90);

        /**
         * Interval for running the stale instance cleanup job.
         */
        private Duration cleanupInterval = Duration.ofSeconds(30);
    }

    @Data
    public static class Outbox {
        /**
         * Enable the transactional outbox pattern for reliable message delivery.
         * When enabled, messages are first written to an outbox collection,
         * then sent by a background relay process.
         */
        private boolean enabled = true;

        /**
         * Interval for polling pending outbox messages.
         */
        private Duration pollInterval = Duration.ofSeconds(1);

        /**
         * Batch size for processing outbox messages.
         */
        private int batchSize = 100;

        /**
         * Maximum number of send attempts before marking a message as failed.
         */
        private int maxAttempts = 5;

        /**
         * Base delay for exponential backoff (in milliseconds).
         */
        private long baseDelayMs = 1000;

        /**
         * Maximum delay for exponential backoff (in milliseconds).
         */
        private long maxDelayMs = 60000;

        /**
         * Retention period for sent messages before cleanup.
         */
        private Duration sentRetention = Duration.ofHours(24);

        /**
         * Interval for cleaning up old sent messages.
         */
        private Duration cleanupInterval = Duration.ofHours(1);

        /**
         * Health check configuration.
         */
        private OutboxHealth health = new OutboxHealth();
    }

    @Data
    public static class OutboxHealth {
        /**
         * Threshold for pending messages before health check reports DOWN.
         */
        private long pendingThreshold = 1000;

        /**
         * Threshold for failed messages before health check reports DOWN.
         * Default is 0, meaning any failed message triggers unhealthy status.
         */
        private long failedThreshold = 0;
    }

    @Data
    public static class MongoDB {
        /**
         * MongoDB connection URI.
         */
        private String uri = "mongodb://localhost:27017/stepprflow";

        /**
         * Database name.
         */
        private String database = "stepprflow";
    }
}

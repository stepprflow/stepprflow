package io.github.stepprflow.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO for registering workflow definitions with the monitoring dashboard.
 *
 * <p>This is a serializable version of WorkflowDefinition
 * without runtime references.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowRegistrationRequest {

    /** Topic used for registration messages via the broker. */
    public static final String REGISTRATION_TOPIC = "stepprflow.registration";

    /** Metadata key for the registration action (REGISTER, HEARTBEAT, DEREGISTER). */
    public static final String METADATA_ACTION = "registration.action";

    /** Metadata key for the service instance ID. */
    public static final String METADATA_INSTANCE_ID = "registration.instanceId";

    /** Action value: register workflows. */
    public static final String ACTION_REGISTER = "REGISTER";

    /** Action value: heartbeat. */
    public static final String ACTION_HEARTBEAT = "HEARTBEAT";

    /** Action value: deregister on shutdown. */
    public static final String ACTION_DEREGISTER = "DEREGISTER";

    /**
     * Action value: the monitor announces it is online and asks every
     * registered service to re-send its full registration. Published by the
     * monitoring server on startup so a monitor that (re)started after the
     * services — and thus missed their one-time REGISTER — repopulates the
     * workflow catalogue (step definitions included) without a service restart.
     */
    public static final String ACTION_ANNOUNCE = "ANNOUNCE";

    /**
     * Service name (application name).
     */
    private String serviceName;

    /**
     * Service instance ID (for multiple instances).
     */
    private String instanceId;

    /**
     * Service host/address.
     */
    private String host;

    /**
     * Service port.
     */
    private int port;

    /**
     * List of workflow definitions.
     */
    private List<WorkflowInfo> workflows;

    /**
     * Workflow information for registration.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkflowInfo {
        /** Topic name. */
        private String topic;
        /** Workflow description. */
        private String description;
        /** Step definitions. */
        private List<StepInfo> steps;
        /** Number of partitions. */
        private int partitions;
        /** Replication factor. */
        private short replication;
        /** Timeout in milliseconds. */
        private Long timeoutMs;
    }

    /**
     * Step information for registration.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepInfo {
        /** Step ID. */
        private int id;
        /** Step label. */
        private String label;
        /** Step description. */
        private String description;
        /** Whether step is skippable. */
        private boolean skippable;
        /** Whether to continue on failure. */
        private boolean continueOnFailure;
        /** Timeout in milliseconds. */
        private Long timeoutMs;
    }
}

package io.github.stepprflow.core.util;

import io.github.stepprflow.core.StepprFlowProperties;

import java.util.List;

/**
 * Single source of truth for the stepprflow topic-suffix naming convention.
 *
 * <p>A workflow's base topic is also used, suffixed, for its lifecycle
 * events: {@code StepExecutor} (the producer) publishes completions on
 * {@code <topic>.completed} and retries on {@code <topic>.retry}, and routes
 * poison messages to {@code <topic><dlq.suffix>}. The monitoring module (a
 * consumer) must subscribe to exactly those same suffixed topics — plus
 * Spring Kafka's own {@code .dlt} (non-blocking retry / dead-letter topic) —
 * to ever see completions, retries, and failures. Before this class existed,
 * both sides hardcoded the suffixes independently and could silently drift,
 * especially the configurable DLQ suffix.</p>
 */
public final class TopicConventions {

    /** Suffix a workflow's completion event is published under. */
    public static final String COMPLETED_SUFFIX = ".completed";

    /** Suffix a workflow's retry message is published under. */
    public static final String RETRY_SUFFIX = ".retry";

    /**
     * Suffix Spring Kafka itself appends for non-blocking retry / dead-letter
     * topics (e.g. {@code @RetryableTopic}). stepprflow does not produce this
     * suffix today, but a monitoring instance scoped to a narrow pattern must
     * still account for it to avoid silently missing such topics.
     */
    public static final String SPRING_DLT_SUFFIX = ".dlt";

    private TopicConventions() {
    }

    /**
     * Builds the completion topic for a base workflow topic.
     *
     * @param baseTopic the workflow's base topic
     * @return {@code baseTopic + ".completed"}
     */
    public static String completedTopic(String baseTopic) {
        return baseTopic + COMPLETED_SUFFIX;
    }

    /**
     * Builds the retry topic for a base workflow topic.
     *
     * @param baseTopic the workflow's base topic
     * @return {@code baseTopic + ".retry"}
     */
    public static String retryTopic(String baseTopic) {
        return baseTopic + RETRY_SUFFIX;
    }

    /**
     * Builds the DLQ topic for a base workflow topic, using the suffix
     * configured on {@code dlq}.
     *
     * @param baseTopic the workflow's base topic
     * @param dlq the DLQ configuration (for its configured suffix)
     * @return {@code baseTopic + dlq.getSuffix()}
     */
    public static String dlqTopic(String baseTopic, StepprFlowProperties.Dlq dlq) {
        return baseTopic + dlqSuffix(dlq);
    }

    /**
     * Resolves the configured DLQ suffix.
     *
     * @param dlq the DLQ configuration
     * @return the configured suffix (e.g. {@code ".dlq"})
     */
    public static String dlqSuffix(StepprFlowProperties.Dlq dlq) {
        return dlq.getSuffix();
    }

    /**
     * Every suffix a workflow's base topic may be published under: the fixed
     * stepprflow suffixes, the configured DLQ suffix, and Spring Kafka's own
     * {@code .dlt}. A consumer scoping its subscription pattern to a set of
     * base topics must cover all of these to never silently miss a lifecycle
     * event.
     *
     * @param dlq the DLQ configuration (for its configured suffix)
     * @return the list of every known suffix
     */
    public static List<String> allSuffixes(StepprFlowProperties.Dlq dlq) {
        return List.of(COMPLETED_SUFFIX, RETRY_SUFFIX, dlqSuffix(dlq), SPRING_DLT_SUFFIX);
    }
}

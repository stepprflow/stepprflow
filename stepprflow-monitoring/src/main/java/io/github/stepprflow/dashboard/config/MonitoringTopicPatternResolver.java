package io.github.stepprflow.dashboard.config;

import io.github.stepprflow.core.StepprFlowProperties;
import io.github.stepprflow.core.model.WorkflowRegistrationRequest;
import io.github.stepprflow.core.util.TopicConventions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Derives the effective Kafka {@code topicPattern} used by
 * {@link io.github.stepprflow.dashboard.listener.MonitoringKafkaListener}.
 *
 * <p>Scoping a topic pattern by hand (SF-5/SF-20) is error-prone: a workflow
 * topic is published on several derived topics — see
 * {@link TopicConventions#allSuffixes} — and forgetting a single one
 * (typically {@code .completed}) silently stops the monitor from ever seeing
 * the matching events. This happened in production: a pattern scoped to
 * {@code ^(...)(\.retry)?$} (missing {@code .completed}) left every
 * execution of the affected workflows stuck IN_PROGRESS forever.</p>
 *
 * <p>{@code stepprflow.kafka.workflow-topics} lets the operator configure the
 * base topics only; this resolver expands each one with every suffix from
 * {@link TopicConventions} — the same class the producer
 * ({@code StepExecutor}) uses to build those topics, so the two can never
 * drift. Resolution order, highest priority first:</p>
 * <ol>
 *   <li>{@code stepprflow.dashboard.topic-pattern} — a key dedicated to the
 *       monitor, so a narrowly-scoped executor pattern is never copy-pasted
 *       here by mistake;</li>
 *   <li>{@code stepprflow.kafka.topic-pattern}, if explicitly configured —
 *       kept for backward compatibility;</li>
 *   <li>the pattern derived from {@code stepprflow.kafka.workflow-topics};</li>
 *   <li>the raw default, {@code ".*"}.</li>
 * </ol>
 * <p>Whichever is picked, a startup warning is logged if the effective
 * pattern does not cover {@code .completed}.</p>
 */
@Slf4j
public class MonitoringTopicPatternResolver {

    /**
     * Dedicated key for the pattern the monitor subscribes with. Takes
     * precedence over everything else. Separate from
     * {@code stepprflow.kafka.topic-pattern} (whose semantics are "topics the
     * executor processes") so a pattern narrowed for the executor is never
     * mistakenly reused as-is for the monitor, which must also see every
     * lifecycle-suffixed topic.
     */
    static final String DASHBOARD_TOPIC_PATTERN_PROPERTY = "stepprflow.dashboard.topic-pattern";

    /**
     * Legacy key, kept for backward compatibility: used only when
     * {@link #DASHBOARD_TOPIC_PATTERN_PROPERTY} is not set.
     */
    static final String LEGACY_TOPIC_PATTERN_PROPERTY = "stepprflow.kafka.topic-pattern";

    private static final String WILDCARD_PATTERN = ".*";

    private final Environment environment;
    private final StepprFlowProperties properties;

    public MonitoringTopicPatternResolver(Environment environment, StepprFlowProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    /**
     * Resolves the pattern the monitoring {@code @KafkaListener} should
     * subscribe to, and logs a startup warning if it does not cover
     * {@code .completed}.
     *
     * @return the effective topic pattern
     */
    public String getEffectivePattern() {
        String effectivePattern = resolve();
        warnIfMissingCompletedSuffix(effectivePattern);
        return effectivePattern;
    }

    private String resolve() {
        String dedicated = environment.getProperty(DASHBOARD_TOPIC_PATTERN_PROPERTY);
        if (dedicated != null && !dedicated.isBlank()) {
            return dedicated;
        }
        if (environment.containsProperty(LEGACY_TOPIC_PATTERN_PROPERTY)) {
            return properties.getKafka().getTopicPattern();
        }
        return derivePatternFromWorkflowTopics(
                properties.getKafka().getWorkflowTopics(),
                properties.getDlq())
                .orElse(properties.getKafka().getTopicPattern());
    }

    /**
     * Builds a regex matching the registration topic, every configured base
     * topic, and each of those topics suffixed with any of
     * {@link TopicConventions#allSuffixes} — or nothing (the base topic
     * itself).
     *
     * @param workflowTopics the configured base topics (without suffix)
     * @param dlq the DLQ configuration (for its configured suffix)
     * @return the derived pattern, or empty if no workflow topics are configured
     */
    static Optional<String> derivePatternFromWorkflowTopics(List<String> workflowTopics, StepprFlowProperties.Dlq dlq) {
        if (workflowTopics == null || workflowTopics.isEmpty()) {
            return Optional.empty();
        }

        List<String> bases = new ArrayList<>();
        bases.add(escape(WorkflowRegistrationRequest.REGISTRATION_TOPIC));
        for (String topic : workflowTopics) {
            if (topic != null && !topic.isBlank()) {
                bases.add(escape(topic.trim()));
            }
        }
        if (bases.size() == 1) {
            // Only the registration topic made it in: no actual workflow topic configured.
            return Optional.empty();
        }

        String suffixes = TopicConventions.allSuffixes(dlq).stream()
                .map(MonitoringTopicPatternResolver::escape)
                .collect(Collectors.joining("|"));
        return Optional.of("^(" + String.join("|", bases) + ")(" + suffixes + ")?$");
    }

    /**
     * The effective topic pattern must always cover {@code .completed},
     * otherwise workflow completions are never received and executions stay
     * IN_PROGRESS forever. The default wildcard {@code ".*"} is exempt (it
     * already matches everything).
     *
     * @param effectivePattern the pattern the monitor is about to subscribe with
     */
    static void warnIfMissingCompletedSuffix(String effectivePattern) {
        if (effectivePattern == null
                || WILDCARD_PATTERN.equals(effectivePattern)
                || effectivePattern.contains(TopicConventions.COMPLETED_SUFFIX)) {
            return;
        }
        log.warn("The effective stepprflow monitoring topic-pattern \"{}\" does not cover "
                + "the \".completed\" suffix: workflow completions on this topic will never "
                + "be received, and executions will stay IN_PROGRESS forever. Configure "
                + "stepprflow.kafka.workflow-topics (base topics only) to let the monitor "
                + "derive a pattern that covers every stepprflow suffix, or include "
                + "\".completed\" explicitly in stepprflow.dashboard.topic-pattern (or the "
                + "legacy stepprflow.kafka.topic-pattern).",
                effectivePattern);
    }

    private static String escape(String value) {
        return value.replace(".", "\\.");
    }
}

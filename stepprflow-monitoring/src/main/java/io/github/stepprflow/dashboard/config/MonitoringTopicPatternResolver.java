package io.github.stepprflow.dashboard.config;

import io.github.stepprflow.core.StepprFlowProperties;
import io.github.stepprflow.core.model.WorkflowRegistrationRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Derives the effective Kafka {@code topicPattern} used by
 * {@link io.github.stepprflow.dashboard.listener.MonitoringKafkaListener}.
 *
 * <p>Scoping {@code stepprflow.kafka.topic-pattern} by hand (SF-5/SF-20) is
 * error-prone: a workflow topic is published on several derived topics —
 * {@code <topic>.completed}, {@code <topic>.retry}, the configured DLQ
 * suffix, and Spring Kafka's {@code .dlt} — and forgetting a single one
 * (typically {@code .completed}) silently stops the monitor from ever seeing
 * the matching events. This happened in production: a pattern scoped to
 * {@code ^(...)(\.retry)?$} (missing {@code .completed}) left every
 * execution of the affected workflows stuck IN_PROGRESS forever.</p>
 *
 * <p>{@code stepprflow.kafka.workflow-topics} lets the operator configure the
 * base topics only; this resolver expands each one with every known suffix.
 * An explicitly configured {@code topic-pattern} still takes precedence, for
 * backward compatibility — but a startup warning is logged if the resulting
 * effective pattern does not cover {@code .completed}.</p>
 */
@Slf4j
public class MonitoringTopicPatternResolver {

    /**
     * The property key that, when present in the environment, means the
     * operator explicitly configured the topic pattern (as opposed to
     * relying on the {@code ${...:.*}} placeholder default).
     */
    static final String TOPIC_PATTERN_PROPERTY = "stepprflow.kafka.topic-pattern";

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
        if (environment.containsProperty(TOPIC_PATTERN_PROPERTY)) {
            return properties.getKafka().getTopicPattern();
        }
        return derivePatternFromWorkflowTopics(
                properties.getKafka().getWorkflowTopics(),
                properties.getDlq().getSuffix())
                .orElse(properties.getKafka().getTopicPattern());
    }

    /**
     * Builds a regex matching the registration topic, every configured base
     * topic, and each of those topics suffixed with {@code .completed},
     * {@code .retry}, the given DLQ suffix, or {@code .dlt} — or nothing
     * (the base topic itself).
     *
     * @param workflowTopics the configured base topics (without suffix)
     * @param dlqSuffix the configured DLQ suffix (e.g. {@code .dlq})
     * @return the derived pattern, or empty if no workflow topics are configured
     */
    static Optional<String> derivePatternFromWorkflowTopics(List<String> workflowTopics, String dlqSuffix) {
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

        String suffixes = String.join("|", "\\.completed", "\\.retry", escape(dlqSuffix), "\\.dlt");
        return Optional.of("^(" + String.join("|", bases) + ")(" + suffixes + ")?$");
    }

    /**
     * SF-?? : the effective topic pattern must always cover {@code
     * .completed}, otherwise workflow completions are never received and
     * executions stay IN_PROGRESS forever. The default wildcard {@code ".*"}
     * is exempt (it already matches everything).
     *
     * @param effectivePattern the pattern the monitor is about to subscribe with
     */
    static void warnIfMissingCompletedSuffix(String effectivePattern) {
        if (effectivePattern == null
                || WILDCARD_PATTERN.equals(effectivePattern)
                || effectivePattern.contains(".completed")) {
            return;
        }
        log.warn("The effective stepprflow monitoring topic-pattern \"{}\" does not cover "
                + "the \".completed\" suffix: workflow completions on this topic will never "
                + "be received, and executions will stay IN_PROGRESS forever. Configure "
                + "stepprflow.kafka.workflow-topics (base topics only) to let the monitor "
                + "derive a pattern that covers every stepprflow suffix, or include "
                + "\".completed\" explicitly in stepprflow.kafka.topic-pattern.",
                effectivePattern);
    }

    private static String escape(String value) {
        return value.replace(".", "\\.");
    }
}

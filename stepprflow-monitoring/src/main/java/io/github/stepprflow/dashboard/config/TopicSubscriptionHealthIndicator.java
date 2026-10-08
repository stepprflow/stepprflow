package io.github.stepprflow.dashboard.config;

import io.github.stepprflow.core.StepprFlowProperties;
import io.github.stepprflow.core.util.TopicConventions;
import io.github.stepprflow.monitor.model.RegisteredWorkflow;
import io.github.stepprflow.monitor.repository.RegisteredWorkflowRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Turns a silently-misconfigured monitoring subscription into an alarm.
 *
 * <p>{@link MonitoringTopicPatternResolver} already logs a startup
 * {@code WARN} when the effective pattern is missing {@code .completed}, but
 * a log line is easy to miss — this is exactly what happened in production:
 * an INT instance ran for a long time with a pattern scoped to
 * {@code ^(...)(\.retry)?$}, every execution of the affected workflows stuck
 * IN_PROGRESS forever, and nobody noticed until a manual investigation. This
 * indicator reports actuator health {@code DOWN} (not just a log) whenever
 * any known base topic, suffixed with any entry of
 * {@link TopicConventions#allSuffixes}, is not matched by the effective
 * pattern.</p>
 *
 * <p>Base topics are collected from both {@code stepprflow.kafka.workflow-topics}
 * (static config) and the live registrations in MongoDB (dynamic — a service
 * may register a workflow the operator never listed in {@code workflow-topics}).
 * If neither source yields any topic, there is nothing to cross-check and the
 * indicator reports {@code UP} (it cannot assert a gap it cannot see).</p>
 */
@Component
public class TopicSubscriptionHealthIndicator implements HealthIndicator {

    private final MonitoringTopicPatternResolver patternResolver;
    private final StepprFlowProperties properties;
    private final RegisteredWorkflowRepository registeredWorkflowRepository;

    public TopicSubscriptionHealthIndicator(
            MonitoringTopicPatternResolver patternResolver,
            StepprFlowProperties properties,
            RegisteredWorkflowRepository registeredWorkflowRepository) {
        this.patternResolver = patternResolver;
        this.properties = properties;
        this.registeredWorkflowRepository = registeredWorkflowRepository;
    }

    @Override
    public Health health() {
        String effectivePattern = patternResolver.getEffectivePattern();
        List<String> baseTopics = new ArrayList<>(collectBaseTopics());
        List<String> suffixes = TopicConventions.allSuffixes(properties.getDlq());

        List<String> uncovered = findUncoveredTopics(effectivePattern, baseTopics, suffixes);

        Health.Builder builder = uncovered.isEmpty() ? Health.up() : Health.down();
        return builder
                .withDetail("effectivePattern", effectivePattern)
                .withDetail("baseTopics", baseTopics)
                .withDetail("checkedSuffixes", suffixes)
                .withDetail("uncoveredTopics", uncovered)
                .build();
    }

    private Set<String> collectBaseTopics() {
        Set<String> topics = new TreeSet<>();

        List<String> configured = properties.getKafka().getWorkflowTopics();
        if (configured != null) {
            configured.stream()
                    .filter(topic -> topic != null && !topic.isBlank())
                    .map(String::trim)
                    .forEach(topics::add);
        }

        registeredWorkflowRepository.findAll().stream()
                .map(RegisteredWorkflow::getTopic)
                .filter(topic -> topic != null && !topic.isBlank())
                .forEach(topics::add);

        return topics;
    }

    /**
     * Cross-checks every {@code baseTopic + suffix} combination against the
     * effective pattern and returns those NOT matched — the topics the
     * monitor would silently never receive.
     *
     * @param effectivePattern the pattern the monitor subscribes with
     * @param baseTopics the known base topics (config + live registry)
     * @param suffixes every suffix a base topic may be published under
     * @return the uncovered {@code baseTopic + suffix} combinations
     */
    static List<String> findUncoveredTopics(
            String effectivePattern, List<String> baseTopics, List<String> suffixes) {
        if (baseTopics.isEmpty()) {
            // Nothing known to cross-check: cannot assert a coverage gap.
            return List.of();
        }

        if (effectivePattern == null) {
            return List.of("<effective pattern is null>");
        }

        Pattern compiled;
        try {
            compiled = Pattern.compile(effectivePattern);
        } catch (PatternSyntaxException e) {
            List<String> invalid = new ArrayList<>();
            invalid.add("<invalid effective pattern: " + effectivePattern + ">");
            return invalid;
        }

        List<String> uncovered = new ArrayList<>();
        for (String base : baseTopics) {
            for (String suffix : suffixes) {
                String topic = base + suffix;
                if (!compiled.matcher(topic).matches()) {
                    uncovered.add(topic);
                }
            }
        }
        return uncovered;
    }
}

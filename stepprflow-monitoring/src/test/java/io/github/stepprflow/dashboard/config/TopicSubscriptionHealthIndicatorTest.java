package io.github.stepprflow.dashboard.config;

import io.github.stepprflow.core.StepprFlowProperties;
import io.github.stepprflow.monitor.model.RegisteredWorkflow;
import io.github.stepprflow.monitor.repository.RegisteredWorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Turns the silent INT incident ("effective pattern misses {@code
 * .completed}, every execution stays IN_PROGRESS forever, nobody notices")
 * into an alarm: this health indicator cross-checks the actual base topics —
 * from both {@code stepprflow.kafka.workflow-topics} AND the live
 * registrations in MongoDB — against every suffix expected by {@code
 * TopicConventions}, and reports DOWN if any combination is not covered by
 * the effective pattern.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TopicSubscriptionHealthIndicator: DOWN when a base topic + suffix is not covered")
class TopicSubscriptionHealthIndicatorTest {

    @Mock
    private MonitoringTopicPatternResolver patternResolver;

    @Mock
    private RegisteredWorkflowRepository registeredWorkflowRepository;

    private StepprFlowProperties properties;

    private TopicSubscriptionHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        properties = new StepprFlowProperties();
        indicator = new TopicSubscriptionHealthIndicator(
                patternResolver, properties, registeredWorkflowRepository);
    }

    @Nested
    @DisplayName("health()")
    class HealthTests {

        @Test
        @DisplayName("DOWN when the effective pattern does not cover .completed for a configured workflow-topic")
        void downWhenCompletedSuffixUncoveredForConfiguredTopic() {
            properties.getKafka().setWorkflowTopics(List.of("invoice-creation"));
            when(patternResolver.getEffectivePattern())
                    .thenReturn("^(invoice-creation)(\\.retry)?$");
            when(registeredWorkflowRepository.findAll()).thenReturn(List.of());

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            @SuppressWarnings("unchecked")
            List<String> uncovered = (List<String>) health.getDetails().get("uncoveredTopics");
            assertThat(uncovered).contains("invoice-creation.completed");
        }

        @Test
        @DisplayName("UP when the effective pattern covers every suffix for every known base topic")
        void upWhenEveryCombinationCovered() {
            properties.getKafka().setWorkflowTopics(List.of("invoice-creation"));
            when(patternResolver.getEffectivePattern())
                    .thenReturn("^(invoice-creation)(\\.completed|\\.retry|\\.dlq|\\.dlt)?$");
            when(registeredWorkflowRepository.findAll()).thenReturn(List.of());

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
        }

        @Test
        @DisplayName("UP when no base topic is known at all (nothing to cross-check)")
        void upWhenNoBaseTopicsKnown() {
            when(patternResolver.getEffectivePattern()).thenReturn(".*");
            when(registeredWorkflowRepository.findAll()).thenReturn(List.of());

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
        }

        @Test
        @DisplayName("cross-checks topics from the live MongoDB registry too, not just the static config")
        void crossChecksRegistryTopicsNotJustStaticConfig() {
            // No workflow-topics configured at all: the only source of truth here
            // is the live registration in MongoDB.
            when(registeredWorkflowRepository.findAll()).thenReturn(List.of(
                    RegisteredWorkflow.builder()
                            .topic("references-events")
                            .serviceName("cockpit-svc-references")
                            .build()));
            when(patternResolver.getEffectivePattern())
                    .thenReturn("^(references-events)(\\.retry)?$");

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            @SuppressWarnings("unchecked")
            List<String> uncovered = (List<String>) health.getDetails().get("uncoveredTopics");
            assertThat(uncovered).contains("references-events.completed");
        }

        @Test
        @DisplayName("DOWN when the effective pattern is syntactically invalid")
        void downWhenEffectivePatternIsInvalidRegex() {
            properties.getKafka().setWorkflowTopics(List.of("invoice-creation"));
            when(patternResolver.getEffectivePattern()).thenReturn("^(unterminated");
            when(registeredWorkflowRepository.findAll()).thenReturn(List.of());

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        }

        @Test
        @DisplayName("includes the effective pattern and base topics in the health details")
        void includesDiagnosticDetails() {
            properties.getKafka().setWorkflowTopics(List.of("invoice-creation"));
            when(patternResolver.getEffectivePattern())
                    .thenReturn("^(invoice-creation)(\\.completed|\\.retry|\\.dlq|\\.dlt)?$");
            when(registeredWorkflowRepository.findAll()).thenReturn(List.of());

            Health health = indicator.health();

            assertThat(health.getDetails().get("effectivePattern"))
                    .isEqualTo("^(invoice-creation)(\\.completed|\\.retry|\\.dlq|\\.dlt)?$");
            @SuppressWarnings("unchecked")
            List<String> baseTopics = (List<String>) health.getDetails().get("baseTopics");
            assertThat(baseTopics).contains("invoice-creation");
        }
    }
}

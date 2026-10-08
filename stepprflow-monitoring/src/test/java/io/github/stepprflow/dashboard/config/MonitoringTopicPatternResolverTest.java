package io.github.stepprflow.dashboard.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.stepprflow.core.StepprFlowProperties;
import io.github.stepprflow.core.model.WorkflowRegistrationRequest;
import io.github.stepprflow.core.util.TopicConventions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The monitor subscribes to Kafka via a regex {@code topicPattern}. Scoping
 * it manually (SF-5) is error-prone: forgetting a suffix — typically
 * {@code .completed} — silently stops the monitor from ever seeing workflow
 * completions, so executions stay IN_PROGRESS forever (this happened in INT
 * with {@code ^(...)(\.retry)?$}). {@code workflow-topics} lets the operator
 * configure base topics only; the monitor derives every suffixed variant
 * itself so none can be forgotten.
 */
@DisplayName("MonitoringTopicPatternResolver: derive a scoped topic-pattern from workflow-topics")
class MonitoringTopicPatternResolverTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(MonitoringTopicPatternResolver.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private static StepprFlowProperties.Dlq dlq(String suffix) {
        StepprFlowProperties.Dlq dlq = new StepprFlowProperties.Dlq();
        dlq.setSuffix(suffix);
        return dlq;
    }

    @Nested
    @DisplayName("derivePatternFromWorkflowTopics()")
    class DerivePatternTests {

        @Test
        @DisplayName("builds a pattern covering the registration topic, every base topic, and every stepprflow suffix")
        void buildsPatternWithAllSuffixes() {
            Optional<String> pattern = MonitoringTopicPatternResolver.derivePatternFromWorkflowTopics(
                    List.of("invoice-creation", "references-events"), dlq(".dlq"));

            String registrationTopicEscaped =
                    WorkflowRegistrationRequest.REGISTRATION_TOPIC.replace(".", "\\.");
            assertThat(pattern).isPresent();
            assertThat(pattern.get()).startsWith(
                    "^(" + registrationTopicEscaped + "|invoice-creation|references-events)(");
            assertThat(pattern.get()).endsWith(")?$");
            assertThat(pattern.get()).contains("\\.completed").contains("\\.retry")
                    .contains("\\.dlq").contains("\\.dlt");
        }

        @Test
        @DisplayName("sources every suffix from TopicConventions.allSuffixes() (single source of truth with the producer)")
        void sourcesSuffixesFromTopicConventions() {
            StepprFlowProperties.Dlq dlqConfig = dlq(".customdlq");
            String pattern = MonitoringTopicPatternResolver
                    .derivePatternFromWorkflowTopics(List.of("invoice-creation"), dlqConfig)
                    .orElseThrow();

            for (String suffix : TopicConventions.allSuffixes(dlqConfig)) {
                assertThat(pattern).contains(suffix.replace(".", "\\."));
                assertThat("invoice-creation" + suffix).matches(pattern);
            }
        }

        @Test
        @DisplayName("honors a custom DLQ suffix instead of hardcoding .dlq")
        void honorsCustomDlqSuffix() {
            Optional<String> pattern = MonitoringTopicPatternResolver.derivePatternFromWorkflowTopics(
                    List.of("orders"), dlq(".deadletter"));

            assertThat(pattern).isPresent();
            assertThat(pattern.get()).contains("\\.deadletter");
            assertThat(pattern.get()).doesNotContain("\\.dlq");
        }

        @Test
        @DisplayName("matches every known suffix for each base topic, and the registration topic, and nothing else")
        void matchesEverySuffixForEachBaseTopic() {
            String pattern = MonitoringTopicPatternResolver
                    .derivePatternFromWorkflowTopics(List.of("invoice-creation"), dlq(".dlq"))
                    .orElseThrow();

            assertThat("invoice-creation").matches(pattern);
            assertThat("invoice-creation.completed").matches(pattern);
            assertThat("invoice-creation.retry").matches(pattern);
            assertThat("invoice-creation.dlq").matches(pattern);
            assertThat("invoice-creation.dlt").matches(pattern);
            assertThat(WorkflowRegistrationRequest.REGISTRATION_TOPIC).matches(pattern);
            assertThat("some-other-topic").doesNotMatch(pattern);
            assertThat("invoice-creation.unknown-suffix").doesNotMatch(pattern);
        }

        @Test
        @DisplayName("returns empty when no workflow topics are configured")
        void returnsEmptyWhenNoTopicsConfigured() {
            assertThat(MonitoringTopicPatternResolver.derivePatternFromWorkflowTopics(List.of(), dlq(".dlq")))
                    .isEmpty();
            assertThat(MonitoringTopicPatternResolver.derivePatternFromWorkflowTopics(null, dlq(".dlq")))
                    .isEmpty();
        }

        @Test
        @DisplayName("ignores blank entries in the configured topic list")
        void ignoresBlankEntries() {
            Optional<String> pattern = MonitoringTopicPatternResolver.derivePatternFromWorkflowTopics(
                    List.of("invoice-creation", "  ", ""), dlq(".dlq"));

            assertThat(pattern).isPresent();
            assertThat("invoice-creation").matches(pattern.get());
        }

        @Test
        @DisplayName("returns empty when the list contains only blank entries")
        void returnsEmptyWhenOnlyBlankEntries() {
            assertThat(MonitoringTopicPatternResolver.derivePatternFromWorkflowTopics(
                    List.of("  ", ""), dlq(".dlq")))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("getEffectivePattern()")
    class GetEffectivePatternTests {

        @Test
        @DisplayName("derives the pattern from workflow-topics when topic-pattern is not explicitly configured")
        void derivesPatternWhenTopicPatternNotSet() {
            MockEnvironment environment = new MockEnvironment();
            StepprFlowProperties properties = new StepprFlowProperties();
            properties.getKafka().setWorkflowTopics(List.of("invoice-creation"));

            MonitoringTopicPatternResolver resolver =
                    new MonitoringTopicPatternResolver(environment, properties);

            String effective = resolver.getEffectivePattern();

            assertThat("invoice-creation.completed").matches(effective);
            assertThat("invoice-creation.retry").matches(effective);
            assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN);
        }

        @Test
        @DisplayName("keeps the explicit topic-pattern even when workflow-topics is also configured")
        void keepsExplicitPatternWhenConfigured() {
            MockEnvironment environment = new MockEnvironment();
            environment.setProperty("stepprflow.kafka.topic-pattern", "myservice\\..*");
            StepprFlowProperties properties = new StepprFlowProperties();
            properties.getKafka().setTopicPattern("myservice\\..*");
            properties.getKafka().setWorkflowTopics(List.of("ignored-topic"));

            MonitoringTopicPatternResolver resolver =
                    new MonitoringTopicPatternResolver(environment, properties);

            assertThat(resolver.getEffectivePattern()).isEqualTo("myservice\\..*");
        }

        @Test
        @DisplayName("falls back to the raw default \".*\" when neither topic-pattern nor workflow-topics are set")
        void fallsBackToDefaultWildcard() {
            MockEnvironment environment = new MockEnvironment();
            StepprFlowProperties properties = new StepprFlowProperties();

            MonitoringTopicPatternResolver resolver =
                    new MonitoringTopicPatternResolver(environment, properties);

            assertThat(resolver.getEffectivePattern()).isEqualTo(".*");
            assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN);
        }

        @Test
        @DisplayName("warns when the explicit topic-pattern omits the .completed suffix (the real INT incident)")
        void warnsWhenExplicitPatternMissesCompletedSuffix() {
            MockEnvironment environment = new MockEnvironment();
            environment.setProperty("stepprflow.kafka.topic-pattern", "^(invoice-creation)(\\.retry)?$");
            StepprFlowProperties properties = new StepprFlowProperties();
            properties.getKafka().setTopicPattern("^(invoice-creation)(\\.retry)?$");

            MonitoringTopicPatternResolver resolver =
                    new MonitoringTopicPatternResolver(environment, properties);

            resolver.getEffectivePattern();

            assertThat(appender.list)
                    .anyMatch(e -> e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains(".completed"));
        }

        @Test
        @DisplayName("stays quiet when workflow-topics derives a pattern that already covers .completed")
        void quietWhenDerivedPatternCoversCompleted() {
            MockEnvironment environment = new MockEnvironment();
            StepprFlowProperties properties = new StepprFlowProperties();
            properties.getKafka().setWorkflowTopics(List.of("invoice-creation"));

            MonitoringTopicPatternResolver resolver =
                    new MonitoringTopicPatternResolver(environment, properties);
            resolver.getEffectivePattern();

            assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN);
        }

        @Test
        @DisplayName("the dedicated stepprflow.dashboard.topic-pattern takes precedence over everything else")
        void dedicatedDashboardPropertyTakesPrecedence() {
            MockEnvironment environment = new MockEnvironment();
            environment.setProperty("stepprflow.dashboard.topic-pattern", "dashboard-scoped\\..*");
            environment.setProperty("stepprflow.kafka.topic-pattern", "legacy-scoped\\..*");
            StepprFlowProperties properties = new StepprFlowProperties();
            properties.getKafka().setTopicPattern("legacy-scoped\\..*");
            properties.getKafka().setWorkflowTopics(List.of("ignored-topic"));

            MonitoringTopicPatternResolver resolver =
                    new MonitoringTopicPatternResolver(environment, properties);

            assertThat(resolver.getEffectivePattern()).isEqualTo("dashboard-scoped\\..*");
        }

        @Test
        @DisplayName("falls back to the legacy stepprflow.kafka.topic-pattern when the dedicated key is not set")
        void fallsBackToLegacyKafkaPropertyWhenDedicatedKeyNotSet() {
            MockEnvironment environment = new MockEnvironment();
            environment.setProperty("stepprflow.kafka.topic-pattern", "legacy-scoped\\..*");
            StepprFlowProperties properties = new StepprFlowProperties();
            properties.getKafka().setTopicPattern("legacy-scoped\\..*");

            MonitoringTopicPatternResolver resolver =
                    new MonitoringTopicPatternResolver(environment, properties);

            assertThat(resolver.getEffectivePattern()).isEqualTo("legacy-scoped\\..*");
        }

        @Test
        @DisplayName("derives from workflow-topics when neither the dedicated nor the legacy key is set")
        void derivesFromWorkflowTopicsWhenNeitherKeyIsSet() {
            MockEnvironment environment = new MockEnvironment();
            StepprFlowProperties properties = new StepprFlowProperties();
            properties.getKafka().setWorkflowTopics(List.of("invoice-creation"));

            MonitoringTopicPatternResolver resolver =
                    new MonitoringTopicPatternResolver(environment, properties);

            assertThat("invoice-creation.completed").matches(resolver.getEffectivePattern());
        }
    }
}

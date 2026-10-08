package io.github.stepprflow.core.util;

import io.github.stepprflow.core.StepprFlowProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Single source of truth for the stepprflow topic-suffix convention. Before
 * this class existed, {@code StepExecutor} hardcoded {@code ".completed"} /
 * {@code ".retry"} as producer-side literals while the monitoring module
 * re-encoded the same suffixes independently to build its subscription
 * pattern — the two could silently drift (especially the configurable DLQ
 * suffix). Both sides must now consume this class.
 */
@DisplayName("TopicConventions: single source of truth for stepprflow topic suffixes")
class TopicConventionsTest {

    @Nested
    @DisplayName("completedTopic() / retryTopic() / dlqTopic()")
    class TopicBuildersTests {

        @Test
        @DisplayName("appends the .completed suffix")
        void buildsCompletedTopic() {
            assertThat(TopicConventions.completedTopic("invoice-creation"))
                    .isEqualTo("invoice-creation.completed");
        }

        @Test
        @DisplayName("appends the .retry suffix")
        void buildsRetryTopic() {
            assertThat(TopicConventions.retryTopic("invoice-creation"))
                    .isEqualTo("invoice-creation.retry");
        }

        @Test
        @DisplayName("appends the configured DLQ suffix")
        void buildsDlqTopicWithConfiguredSuffix() {
            StepprFlowProperties.Dlq dlq = new StepprFlowProperties.Dlq();
            dlq.setSuffix(".deadletter");

            assertThat(TopicConventions.dlqTopic("invoice-creation", dlq))
                    .isEqualTo("invoice-creation.deadletter");
        }

        @Test
        @DisplayName("defaults the DLQ suffix to .dlq")
        void defaultsDlqSuffix() {
            StepprFlowProperties.Dlq dlq = new StepprFlowProperties.Dlq();

            assertThat(TopicConventions.dlqTopic("invoice-creation", dlq))
                    .isEqualTo("invoice-creation.dlq");
        }
    }

    @Nested
    @DisplayName("allSuffixes()")
    class AllSuffixesTests {

        @Test
        @DisplayName("includes .completed, .retry, the configured DLQ suffix, and Spring's .dlt")
        void listsEveryKnownSuffix() {
            StepprFlowProperties.Dlq dlq = new StepprFlowProperties.Dlq();
            dlq.setSuffix(".customdlq");

            assertThat(TopicConventions.allSuffixes(dlq))
                    .containsExactlyInAnyOrder(".completed", ".retry", ".customdlq", ".dlt");
        }

        @Test
        @DisplayName("uses the default .dlq when the DLQ suffix is left at its default")
        void listsDefaultDlqSuffix() {
            StepprFlowProperties.Dlq dlq = new StepprFlowProperties.Dlq();

            assertThat(TopicConventions.allSuffixes(dlq))
                    .containsExactlyInAnyOrder(".completed", ".retry", ".dlq", ".dlt");
        }
    }
}

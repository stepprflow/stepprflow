package io.github.stepprflow.broker.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * SF-20: the dangerous default topic pattern {@code ".*"} (subscribe to every
 * topic on the cluster) must never be silent. Verifies the startup warning is
 * emitted for {@code ".*"} and stays quiet for a scoped pattern.
 */
@DisplayName("SF-20: wildcard topic-pattern startup warning")
class TopicPatternWarningTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(KafkaBrokerAutoConfiguration.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("warns when the pattern is the wildcard default \".*\"")
    void warnsOnWildcard() {
        KafkaBrokerAutoConfiguration.warnIfWildcardTopicPattern(".*");

        assertThat(appender.list)
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("topic-pattern")
                        && e.getFormattedMessage().contains("EVERY topic"));
    }

    @Test
    @DisplayName("stays quiet for a scoped pattern")
    void quietOnScopedPattern() {
        KafkaBrokerAutoConfiguration.warnIfWildcardTopicPattern("myservice\\..*");

        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN);
    }

    @Test
    @DisplayName("stays quiet when the pattern is null")
    void quietOnNull() {
        KafkaBrokerAutoConfiguration.warnIfWildcardTopicPattern(null);

        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN);
    }
}

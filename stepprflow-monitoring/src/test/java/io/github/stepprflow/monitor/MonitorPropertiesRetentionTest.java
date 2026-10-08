package io.github.stepprflow.monitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code stepprflow.monitor.retention.*} properties actually
 * bind to {@link MonitorProperties.Retention} (F6: the former
 * {@code stepprflow.monitor.cleanup.*} keys bound to nothing).
 */
@DisplayName("MonitorProperties retention binding")
class MonitorPropertiesRetentionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("Should default to enabled=true and maxAge=30 days when nothing is configured")
    void shouldDefaultToThirtyDaysEnabled() {
        contextRunner.run(context -> {
            MonitorProperties properties = context.getBean(MonitorProperties.class);
            assertThat(properties.getRetention().isEnabled()).isTrue();
            assertThat(properties.getRetention().effectiveMaxAge()).isEqualTo(Duration.ofDays(30));
            assertThat(properties.getRetention().effectiveCompletedTtl()).isEqualTo(Duration.ofDays(30));
            assertThat(properties.getRetention().effectiveFailedTtl()).isEqualTo(Duration.ofDays(30));
        });
    }

    @Test
    @DisplayName("Should bind stepprflow.monitor.retention.max-age")
    void shouldBindMaxAge() {
        contextRunner
                .withPropertyValues("stepprflow.monitor.retention.max-age=P45D")
                .run(context -> {
                    MonitorProperties properties = context.getBean(MonitorProperties.class);
                    assertThat(properties.getRetention().getMaxAge()).isEqualTo(Duration.ofDays(45));
                    assertThat(properties.getRetention().effectiveCompletedTtl()).isEqualTo(Duration.ofDays(45));
                });
    }

    @Test
    @DisplayName("Should bind stepprflow.monitor.retention.enabled=false")
    void shouldBindEnabledFalse() {
        contextRunner
                .withPropertyValues("stepprflow.monitor.retention.enabled=false")
                .run(context -> {
                    MonitorProperties properties = context.getBean(MonitorProperties.class);
                    assertThat(properties.getRetention().isEnabled()).isFalse();
                });
    }

    @Test
    @DisplayName("Should bind optional completed-ttl/failed-ttl overrides independently of max-age")
    void shouldBindCompletedAndFailedTtlOverrides() {
        contextRunner
                .withPropertyValues(
                        "stepprflow.monitor.retention.max-age=P30D",
                        "stepprflow.monitor.retention.completed-ttl=P5D",
                        "stepprflow.monitor.retention.failed-ttl=P60D")
                .run(context -> {
                    MonitorProperties properties = context.getBean(MonitorProperties.class);
                    assertThat(properties.getRetention().effectiveCompletedTtl()).isEqualTo(Duration.ofDays(5));
                    assertThat(properties.getRetention().effectiveFailedTtl()).isEqualTo(Duration.ofDays(60));
                    assertThat(properties.getRetention().effectiveMaxAge()).isEqualTo(Duration.ofDays(30));
                });
    }

    @Test
    @DisplayName("Should bind stepprflow.monitor.retention.cleanup-cron")
    void shouldBindCleanupCron() {
        contextRunner
                .withPropertyValues("stepprflow.monitor.retention.cleanup-cron=0 0 */6 * * ?")
                .run(context -> {
                    MonitorProperties properties = context.getBean(MonitorProperties.class);
                    assertThat(properties.getRetention().getCleanupCron()).isEqualTo("0 0 */6 * * ?");
                });
    }

    @Configuration
    @EnableConfigurationProperties(MonitorProperties.class)
    static class TestConfig {
    }
}

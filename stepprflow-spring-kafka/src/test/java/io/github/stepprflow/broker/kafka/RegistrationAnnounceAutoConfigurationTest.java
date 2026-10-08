package io.github.stepprflow.broker.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.stepprflow.core.broker.MessageBroker;
import io.github.stepprflow.core.registration.RegistrationAutoConfiguration;
import io.github.stepprflow.core.registration.WorkflowRegistrationClient;
import io.github.stepprflow.core.service.WorkflowRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Regression guard for the ANNOUNCE handshake wiring.
 *
 * <p>The listener depends on a {@link WorkflowRegistrationClient} that a
 * <em>later</em> auto-configuration ({@link RegistrationAutoConfiguration}, itself
 * conditional on a {@code MessageBroker}) creates. When the listener bean lived
 * inside {@code KafkaBrokerAutoConfiguration}, its
 * {@code @ConditionalOnBean(WorkflowRegistrationClient.class)} was evaluated
 * before that client existed and the bean was silently never created — the
 * handshake was dead at runtime while every unit test still passed. This test
 * exercises the real auto-configuration ordering.</p>
 */
@DisplayName("RegistrationAnnounceAutoConfiguration wiring")
class RegistrationAnnounceAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RegistrationAutoConfiguration.class,
                    RegistrationAnnounceAutoConfiguration.class));

    @Test
    @DisplayName("Creates the announce listener once a WorkflowRegistrationClient is present")
    void listenerIsWiredWhenRegistrationClientPresent() {
        runner
                .withBean(MessageBroker.class, () -> mock(MessageBroker.class))
                .withBean(WorkflowRegistry.class, () -> mock(WorkflowRegistry.class))
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(WorkflowRegistrationClient.class);
                    assertThat(ctx).hasSingleBean(RegistrationAnnounceListener.class);
                });
    }

    @Test
    @DisplayName("No announce listener when the service does not register workflows")
    void listenerAbsentWhenNoRegistrationClient() {
        // No MessageBroker -> RegistrationAutoConfiguration is off -> no client ->
        // no listener. A pure monitoring/consumer app therefore never reacts to
        // its own announcement.
        runner.run(ctx ->
                assertThat(ctx).doesNotHaveBean(RegistrationAnnounceListener.class));
    }
}

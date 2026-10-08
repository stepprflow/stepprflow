package io.github.stepprflow.broker.kafka;

import io.github.stepprflow.core.registration.RegistrationAutoConfiguration;
import io.github.stepprflow.core.registration.WorkflowRegistrationClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Wires the {@link RegistrationAnnounceListener} so a producer re-registers when
 * the monitor publishes an ANNOUNCE.
 *
 * <p>This must be a dedicated auto-configuration ordered
 * {@link AutoConfigureAfter @AutoConfigureAfter}
 * {@link RegistrationAutoConfiguration}: the {@link WorkflowRegistrationClient}
 * the listener depends on is created there, and {@code RegistrationAutoConfiguration}
 * is itself {@code @ConditionalOnBean(MessageBroker.class)} so it runs after the
 * Kafka broker auto-configuration. Declaring the listener bean inside
 * {@code KafkaBrokerAutoConfiguration} (as it was first written) evaluates its
 * {@code @ConditionalOnBean(WorkflowRegistrationClient.class)} before the client
 * exists — the condition is false and the bean is silently never created, so the
 * handshake is dead at runtime. {@code RegistrationAnnounceAutoConfigurationTest}
 * guards against that regression.</p>
 */
@AutoConfiguration
@AutoConfigureAfter(RegistrationAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty(name = "stepprflow.broker", havingValue = "kafka", matchIfMissing = true)
public class RegistrationAnnounceAutoConfiguration {

    /**
     * Listens on the registration topic for the monitor's ANNOUNCE. Only created
     * when this service actually registers workflows (a
     * {@link WorkflowRegistrationClient} is present).
     *
     * @param registrationClient the registration client to re-trigger
     * @return the announce listener
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(WorkflowRegistrationClient.class)
    public RegistrationAnnounceListener registrationAnnounceListener(
            final WorkflowRegistrationClient registrationClient) {
        return new RegistrationAnnounceListener(registrationClient);
    }
}

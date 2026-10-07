package io.github.stepprflow.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.stepprflow.core.idempotency.IdempotencyStore;
import io.github.stepprflow.core.idempotency.InMemoryIdempotencyStore;
import io.github.stepprflow.core.security.NoOpSecurityContextPropagator;
import io.github.stepprflow.core.security.SecurityContextPropagator;
import io.github.stepprflow.core.service.StepExecutor;
import io.github.stepprflow.core.service.WorkflowRegistry;
import io.github.stepprflow.core.service.WorkflowStarterImpl;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/**
 * Auto-configuration for StepprFlow core components.
 * This configures the core workflow infrastructure.
 * Broker-specific configurations (Kafka, RabbitMQ) are in separate modules.
 */
@AutoConfiguration
@EnableConfigurationProperties(StepprFlowProperties.class)
@ConditionalOnProperty(prefix = "stepprflow", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Import({
        WorkflowRegistry.class,
        StepExecutor.class,
        WorkflowStarterImpl.class
})
@ComponentScan(basePackages = "io.github.stepprflow.core")
public class StepprFlowAutoConfiguration {

    /**
     * Default security context propagator (no-op).
     * Users can provide their own implementation to propagate security context.
     *
     * @return the default no-op security context propagator
     */
    @Bean
    @ConditionalOnMissingBean(SecurityContextPropagator.class)
    public SecurityContextPropagator securityContextPropagator() {
        return new NoOpSecurityContextPropagator();
    }

    /**
     * SF-8: default in-memory de-duplication store. A distributed store (e.g.
     * {@code stepprflow-idempotency-redis}) replaces it by defining its own
     * {@link IdempotencyStore} bean. The store is always present so the executor
     * can inject it; it is only consulted when
     * {@code stepprflow.idempotency.enabled=true}.
     *
     * @param properties the stepprflow properties
     * @return the default in-memory idempotency store
     */
    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    public IdempotencyStore idempotencyStore(final StepprFlowProperties properties) {
        StepprFlowProperties.Idempotency cfg = properties.getIdempotency();
        return new InMemoryIdempotencyStore(cfg.getTtl(), cfg.getInMemoryMaxSize());
    }

    /**
     * ObjectMapper configured for workflow payload serialization/deserialization.
     * This mapper is lenient to handle domain objects with computed properties
     * (getters without setters) without requiring Jackson annotations.
     *
     * @return the stepprflow ObjectMapper
     */
    @Bean("stepprflowObjectMapper")
    public ObjectMapper stepprflowObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // Ignore properties in JSON that don't have setters (computed getters)
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }
}

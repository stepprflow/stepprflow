package io.github.stepprflow.idempotency.redis;

import io.github.stepprflow.core.StepprFlowAutoConfiguration;
import io.github.stepprflow.core.StepprFlowProperties;
import io.github.stepprflow.core.idempotency.IdempotencyStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Registers a {@link RedisIdempotencyStore} when {@code
 * stepprflow.idempotency.store=redis}. Ordered after Spring Boot's Redis
 * auto-configuration (so a {@link StringRedisTemplate} exists) and before the
 * core auto-configuration (so this bean wins over the in-memory default via
 * {@code @ConditionalOnMissingBean}).
 */
@AutoConfiguration(after = RedisAutoConfiguration.class,
        before = StepprFlowAutoConfiguration.class)
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnProperty(prefix = "stepprflow.idempotency", name = "store",
        havingValue = "redis")
@EnableConfigurationProperties(StepprFlowProperties.class)
public class RedisIdempotencyAutoConfiguration {

    /**
     * The distributed Redis idempotency store.
     *
     * @param redisTemplate the Redis template (from Spring Boot Redis auto-config)
     * @param properties the stepprflow properties
     * @return the Redis-backed idempotency store
     */
    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    public IdempotencyStore redisIdempotencyStore(final StringRedisTemplate redisTemplate,
                                                  final StepprFlowProperties properties) {
        StepprFlowProperties.Idempotency cfg = properties.getIdempotency();
        return new RedisIdempotencyStore(redisTemplate, cfg.getKeyPrefix(), cfg.getTtl());
    }
}

package io.github.stepprflow.idempotency.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.stepprflow.core.idempotency.IdempotencyKey;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * SF-8: integration test for the Redis-backed idempotency store against a real
 * Redis (Testcontainers). Runs in surefire (named {@code *IntegrationTest}) so
 * it executes in the standard build like the other adapter integration tests.
 */
@Testcontainers
@DisplayName("RedisIdempotencyStore (SF-8)")
class RedisIdempotencyStoreIntegrationTest {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        REDIS.stop();
    }

    private RedisIdempotencyStore store(final Duration ttl) {
        return new RedisIdempotencyStore(redisTemplate, "stepprflow:idem:test:", ttl);
    }

    @Test
    @DisplayName("marks a key processed and reads it back")
    void marksAndReads() {
        RedisIdempotencyStore store = store(Duration.ofMinutes(10));
        IdempotencyKey k = new IdempotencyKey("exec-redis-1", 1);

        assertThat(store.isProcessed(k)).isFalse();
        store.markProcessed(k);
        assertThat(store.isProcessed(k)).isTrue();
    }

    @Test
    @DisplayName("distinct steps are independent")
    void independentSteps() {
        RedisIdempotencyStore store = store(Duration.ofMinutes(10));
        store.markProcessed(new IdempotencyKey("exec-redis-2", 1));

        assertThat(store.isProcessed(new IdempotencyKey("exec-redis-2", 1))).isTrue();
        assertThat(store.isProcessed(new IdempotencyKey("exec-redis-2", 2))).isFalse();
    }

    @Test
    @DisplayName("a key expires after its TTL")
    void expiresAfterTtl() {
        RedisIdempotencyStore store = store(Duration.ofSeconds(1));
        IdempotencyKey k = new IdempotencyKey("exec-redis-3", 1);
        store.markProcessed(k);
        assertThat(store.isProcessed(k)).isTrue();

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(store.isProcessed(k)).isFalse());
    }
}

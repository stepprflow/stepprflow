package io.github.stepprflow.idempotency.redis;

import io.github.stepprflow.core.idempotency.IdempotencyKey;
import io.github.stepprflow.core.idempotency.IdempotencyStore;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis-backed {@link IdempotencyStore} (SF-8): distributed de-duplication that
 * survives restarts and is shared across instances.
 *
 * <p>A processed key is stored as {@code <prefix><executionId>:<step>} with a
 * TTL, so the store self-expires and needs no cleanup. {@code isProcessed} is an
 * existence check; {@code markProcessed} writes the key with the configured TTL.
 */
public class RedisIdempotencyStore implements IdempotencyStore {

    /** The Redis template. */
    private final StringRedisTemplate redisTemplate;
    /** Namespacing prefix for keys. */
    private final String keyPrefix;
    /** How long a processed key is retained. */
    private final Duration ttl;

    /**
     * Creates a Redis idempotency store.
     *
     * @param redisTemplate the Redis template
     * @param keyPrefix the key prefix used to namespace entries
     * @param ttl how long a processed key is retained
     */
    public RedisIdempotencyStore(final StringRedisTemplate redisTemplate,
                                 final String keyPrefix,
                                 final Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
        this.ttl = ttl;
    }

    private String redisKey(final IdempotencyKey key) {
        return keyPrefix + key.asString();
    }

    @Override
    public boolean isProcessed(final IdempotencyKey key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(redisKey(key)));
    }

    @Override
    public void markProcessed(final IdempotencyKey key) {
        redisTemplate.opsForValue().set(redisKey(key), "1", ttl);
    }
}

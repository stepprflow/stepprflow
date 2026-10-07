package io.github.stepprflow.core.idempotency;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Bounded, in-memory {@link IdempotencyStore} (SF-8 default).
 *
 * <p>Keys are retained with a TTL and the map is capped as an LRU, so memory is
 * bounded regardless of volume. This implementation is per-instance only: it
 * does not survive a restart and does not de-duplicate across instances — use
 * {@code stepprflow-idempotency-redis} for distributed de-duplication.
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    /** Maps a key string to its expiry timestamp (ms). */
    private final Map<String, Long> seen;
    /** TTL in milliseconds. */
    private final long ttlMillis;
    /** Clock, in epoch milliseconds (injectable for testing). */
    private final LongSupplier nowMillis;

    /**
     * Creates a store with the system clock.
     *
     * @param ttl how long a processed key is remembered
     * @param maxSize maximum number of keys retained (LRU beyond this)
     */
    public InMemoryIdempotencyStore(final Duration ttl, final int maxSize) {
        this(ttl, maxSize, System::currentTimeMillis);
    }

    /**
     * Creates a store with an explicit clock (for testing).
     *
     * @param ttl how long a processed key is remembered
     * @param maxSize maximum number of keys retained (LRU beyond this)
     * @param clock supplies the current time in epoch milliseconds
     */
    public InMemoryIdempotencyStore(final Duration ttl, final int maxSize,
                                    final LongSupplier clock) {
        this.ttlMillis = ttl.toMillis();
        this.nowMillis = clock;
        final int cap = maxSize > 0 ? maxSize : 1;
        this.seen = new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(final Map.Entry<String, Long> eldest) {
                return size() > cap;
            }
        };
    }

    @Override
    public synchronized boolean isProcessed(final IdempotencyKey key) {
        Long expiry = seen.get(key.asString());
        if (expiry == null) {
            return false;
        }
        if (nowMillis.getAsLong() >= expiry) {
            seen.remove(key.asString());
            return false;
        }
        return true;
    }

    @Override
    public synchronized void markProcessed(final IdempotencyKey key) {
        seen.put(key.asString(), nowMillis.getAsLong() + ttlMillis);
    }
}

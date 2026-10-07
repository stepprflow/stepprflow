package io.github.stepprflow.core.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SF-8: unit tests for the bounded in-memory idempotency store. Time is driven
 * by an injected clock so TTL expiry is deterministic (no sleeps).
 */
@DisplayName("InMemoryIdempotencyStore (SF-8)")
class InMemoryIdempotencyStoreTest {

    private final AtomicLong now = new AtomicLong(0L);

    private InMemoryIdempotencyStore store(final Duration ttl, final int maxSize) {
        return new InMemoryIdempotencyStore(ttl, maxSize, now::get);
    }

    private static IdempotencyKey key(final String exec, final int step) {
        return new IdempotencyKey(exec, step);
    }

    @Test
    @DisplayName("a key is not processed until marked, then is")
    void marksAndReports() {
        InMemoryIdempotencyStore store = store(Duration.ofMinutes(10), 100);
        IdempotencyKey k = key("exec-1", 1);

        assertThat(store.isProcessed(k)).isFalse();
        store.markProcessed(k);
        assertThat(store.isProcessed(k)).isTrue();
    }

    @Test
    @DisplayName("distinct steps of the same execution are independent")
    void stepsAreIndependent() {
        InMemoryIdempotencyStore store = store(Duration.ofMinutes(10), 100);
        store.markProcessed(key("exec-1", 1));

        assertThat(store.isProcessed(key("exec-1", 1))).isTrue();
        assertThat(store.isProcessed(key("exec-1", 2))).isFalse();
    }

    @Test
    @DisplayName("a key is forgotten once its TTL elapses")
    void expiresAfterTtl() {
        InMemoryIdempotencyStore store = store(Duration.ofMillis(1000), 100);
        IdempotencyKey k = key("exec-1", 1);
        store.markProcessed(k);
        assertThat(store.isProcessed(k)).isTrue();

        now.addAndGet(1001); // advance past TTL

        assertThat(store.isProcessed(k)).isFalse();
    }

    @Test
    @DisplayName("the store is bounded: the eldest key is evicted past capacity")
    void boundedByMaxSize() {
        InMemoryIdempotencyStore store = store(Duration.ofMinutes(10), 2);
        store.markProcessed(key("e", 1));
        store.markProcessed(key("e", 2));
        store.markProcessed(key("e", 3)); // evicts (e,1)

        assertThat(store.isProcessed(key("e", 1))).isFalse();
        assertThat(store.isProcessed(key("e", 2))).isTrue();
        assertThat(store.isProcessed(key("e", 3))).isTrue();
    }
}

# ADR-0005: Idempotency / de-duplication

- Status: Accepted
- Date: 2026-10-07

## Context

stepprflow delivers at-least-once (ADR-0002): a step handler can run more than
once on redelivery. ADR-0002 placed the burden entirely on handlers ("must be
idempotent"), but offered no mechanism, so every consumer had to reinvent
de-duplication (audit finding SF-8). core deliberately has no persistence
dependency, so any built-in de-duplication must not force one on consumers that
do not want it.

## Decision

Provide **opt-in, best-effort** de-duplication behind an SPI:

- core defines `IdempotencyStore` (`isProcessed(key)` / `markProcessed(key)`)
  keyed by `(executionId, step)`, and ships a bounded in-memory default
  (LRU + TTL, per-instance).
- `stepprflow-idempotency-redis` provides a distributed implementation
  (`SET key 1 PX <ttl>` / `EXISTS`) for de-duplication across instances and
  restarts.
- Enabled by `stepprflow.idempotency.enabled=true` (default **false**); the
  backing store is selected by `stepprflow.idempotency.store=inmemory|redis`.
- The executor checks `isProcessed` **before** running a step (a hit is skipped
  and acknowledged) and calls `markProcessed` **only after** the step succeeds
  and its next message is durably produced (record-after-success). A failing
  step is never recorded, so it is retried normally. The check is fail-open and
  recording failures never trigger a retry.

## Consequences

- Duplicates are strongly reduced without forcing a persistence dependency on
  core; the feature is off by default, so existing behaviour is unchanged.
- This is **effectively-once, best-effort, not exactly-once**. Residual
  duplicate windows remain: a crash between producing the next message and
  recording the key, and two concurrent in-flight consumers of the same key
  (rebalance). Eliminating these requires a transactional outbox, which is out
  of scope — handlers that need a hard guarantee must still de-duplicate inside
  their own business transaction.
- The TTL must exceed the broker's redelivery/retry window, or a late
  redelivery is re-processed.

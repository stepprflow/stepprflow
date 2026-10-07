# ADR-0006: SPI extension points & contracts

- Status: Accepted
- Date: 2026-10-07

## Context

stepprflow is extended by implementing a handful of interfaces in
`stepprflow-core`. The audit (SF-18) found these extension points had no stated
behavioral contract: the signatures compiled, but an implementer could not tell
what each method must guarantee — and several engine invariants (at-least-once,
no security-context leak) silently depend on those guarantees. A wrong
implementation breaks the engine in ways the type system does not catch.

## Decision

The following are the supported SPIs. Each now documents its contract in its
javadoc; this ADR is the index and states stability.

- **`broker.MessageBroker`** — produce messages. `sendSync` MUST block until the
  message is durably accepted and throw otherwise (the at-least-once
  guarantee of ADR-0002 depends on it); `send`/`sendAsync` are best-effort and
  off the critical path. Adapters: Kafka, RabbitMQ.
- **`security.SecurityContextPropagator`** — move the caller's security context
  across steps. `capture` runs on the caller thread; `restore`/`clear` bracket
  one step on a pooled engine thread and must stay thread-confined and
  leak-free (ADR-0003). Default: `NoOpSecurityContextPropagator`.
- **`idempotency.IdempotencyStore`** — best-effort de-duplication
  (`isProcessed` / `markProcessed`), recorded after success (ADR-0005).
  Implementations: in-memory (default), Redis.
- **`service.WorkflowExecutionStore`** — optional persistence of execution
  state for the monitor; not required by the core engine.

These interfaces are the stable extension surface: changes to them are breaking
and require a superseding ADR. Each implementation is expected to be verified by
its own tests against the documented contract; a shared cross-implementation
contract test harness is a possible future enhancement (currently each
implementation carries its own behavioral tests).

## Consequences

- Third-party implementers have a written contract per extension point, so the
  engine's hidden invariants are no longer tribal knowledge.
- The contracts are normative: an implementation that violates them (e.g. a
  non-blocking `sendSync`) is a bug in the implementation, not the engine.

# ADR-0002: At-least-once delivery contract

- Status: Accepted
- Date: 2026-10-07

## Context

A workflow step advances by producing the next message to the broker before the
consumed message is acknowledged. An earlier implementation acknowledged the
consumed record before confirming the produced message, so a broker failure
between the two silently lost the workflow (audit finding SF-4). Steps may also
be redelivered by the broker, and a handler can run more than once.

## Decision

We will provide **at-least-once** delivery, never at-most-once:

- All workflow-critical production (advance / retry / complete / DLQ) uses
  synchronous, confirmed sends; the consumed record is acknowledged **only after**
  the produced message is durably persisted.
- On an un-acked record the consumer seeks back and redelivers with a bounded
  backoff, retrying indefinitely until processing succeeds; infrastructure
  failures are never skipped.
- A poison message (untrusted payload type, forged/expired security context,
  exhausted retries) is routed to the DLQ, after which the record is acked, so
  it does not loop forever.

## Consequences

- No workflow message is silently lost on a broker/producer failure.
- A partition can be blocked while a message is retried under unlimited backoff
  (head-of-line blocking is accepted by design; monitoring surfaces it).
- **Step handlers must be idempotent** — a step can legitimately run more than
  once. De-duplication is not yet provided by the framework (tracked separately).

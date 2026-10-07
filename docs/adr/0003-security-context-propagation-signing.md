# ADR-0003: Security context propagation & signing

- Status: Accepted
- Date: 2026-10-07

## Context

A workflow captures the caller's security context (e.g. a JWT) and carries it in
the message so each step runs under the originator's identity. Two audit
findings applied: the context was restored onto the pooled consumer thread
outside a guarded try/finally, leaking it across executions and poison-pilling
the broker when restore threw (SF-1); and the context travelled unsigned, so a
crafted message could forge an identity (SF-3).

## Decision

- The context is captured on the **caller's thread** (never an async worker),
  restored inside a try/finally in the executor, and **always cleared** in the
  finally — including on the bounded timeout worker — so it never outlives one
  step execution on a pooled thread.
- A restore/verify failure is terminal and **non-retryable**: it goes straight
  to the DLQ, never redelivered (the credential is embedded and would fail
  identically).
- The context envelope is **HMAC-signed**, bound to `executionId`+`topic`, and
  verified before restore. Signing is enabled by configuring a shared secret
  (`stepprflow.security.context-signing.secret`); when unset, signing is
  disabled and a one-time warning is logged.

## Consequences

- No credential leak across pooled threads; a forged/tampered/replayed context
  is rejected and DLQ'd rather than restored.
- Every service participating in the same workflows must share the same signing
  secret; a missing or mismatched secret disables integrity protection (warned).
- The raw context value is never logged — only its presence.

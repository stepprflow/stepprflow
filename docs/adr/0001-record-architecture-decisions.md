# ADR-0001: Record architecture decisions

- Status: Accepted
- Date: 2026-10-07

## Context

stepprflow's significant design decisions (delivery semantics, security model,
authentication) lived only in code and commit history. A 2026 security audit
found several hardening measures that were implemented but not wired, partly
because the intended contract was never written down — so reviewers could not
tell deliberate choices from accidents.

## Decision

We will record architecture decisions as lightweight MADR-style records under
`docs/adr/`, numbered sequentially and immutable once accepted. A decision is
changed by adding a superseding ADR, never by editing an accepted one.

## Consequences

- Significant decisions have a single, reviewable home; PRs that change a
  contract are expected to add or supersede an ADR.
- A small amount of process overhead per significant change.
- The existing remediation decisions are backfilled as ADR-0002…0004.

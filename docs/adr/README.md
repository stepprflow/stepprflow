# Architecture Decision Records

This directory records the significant architecture decisions made on
stepprflow, using lightweight [MADR](https://adr.github.io/madr/)-style records.

Each record is immutable once accepted: to change a decision, add a new ADR that
supersedes the old one (and mark the old one `Superseded by ADR-XXXX`) rather
than editing history.

## Index

- [ADR-0001](0001-record-architecture-decisions.md) — Record architecture decisions
- [ADR-0002](0002-at-least-once-delivery-contract.md) — At-least-once delivery contract
- [ADR-0003](0003-security-context-propagation-signing.md) — Security context propagation & signing
- [ADR-0004](0004-monitor-dual-mode-authentication.md) — Monitor dual-mode authentication

## Adding a new ADR

1. Copy [`0000-template.md`](0000-template.md) to `NNNN-short-title.md` (next number).
2. Fill it in, set the status to `Proposed`, open a PR.
3. On merge, set the status to `Accepted` and add it to the index above.

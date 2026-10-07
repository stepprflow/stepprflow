# ADR-0004: Monitor dual-mode authentication

- Status: Accepted
- Date: 2026-10-07

## Context

The monitoring API/UI/WebSocket endpoints were unauthenticated (audit finding
SF-5). Deployments differ: some want simple local credentials, others want SSO
against an existing Keycloak/OIDC provider. Forcing one model would not fit both.

## Decision

Authentication mode is selected per deployment by
`stepprflow.monitor.auth.mode = basic | oidc`, with a **fail-closed** default:
if the mode is unset the filter chain denies all requests.

- `basic`: form/HTTP-Basic login against configured local credentials.
- `oidc`: OAuth2 login (redirect) against a configurable `issuer-uri`.
- Authorization is role-based: reads require `VIEWER` or `OPERATOR`, mutations
  require `OPERATOR`.
- The SPA uses a deferred CSRF token written to an `XSRF-TOKEN` cookie
  (`CsrfCookieFilter` + plain `CsrfTokenRequestAttributeHandler`).

## Consequences

- Each monitor instance **must** set `stepprflow.monitor.auth.mode`; otherwise it
  is inaccessible (fail-closed, by design). This is a required deployment step.
- A single instance runs one mode at a time; the operator chooses `basic` or
  `oidc` per deployment, not both simultaneously.

/**
 * Security configuration for the monitoring module (SF-5).
 *
 * <p>This package provides dual-mode authentication (basic or OIDC, selected per
 * deployment by {@code stepprflow.monitor.auth.mode}) and the authorization rules
 * for the monitoring API, dashboard and WebSocket: reads require authentication
 * while mutations require the OPERATOR authority. An unset mode denies every
 * request (fail-closed).</p>
 */
package io.github.stepprflow.monitor.security;

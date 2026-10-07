# Security Context Propagation

Steppr Flow supports automatic propagation of security context (authentication/authorization) across workflow steps. This ensures that each step executes with the same security principal as the original workflow initiator.

## Overview

When a workflow is started from an authenticated HTTP request, the security context (e.g., JWT token) is:
1. **Captured** at workflow start time
2. **Stored** in the workflow message
3. **Restored** before each step execution
4. **Cleared** after step execution

This allows steps to access the authenticated user's information and enforce authorization checks.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        HTTP Request (with JWT)                       │
│                               │                                      │
│                               ▼                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │                   REST Controller                            │    │
│  │   workflowStarter.start("my-workflow", payload)              │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                               │                                      │
│                               ▼                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │              SecurityContextPropagator.capture()             │    │
│  │              Returns: JWT token string                        │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                               │                                      │
│                               ▼                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │                    WorkflowMessage                           │    │
│  │   • executionId: "abc-123"                                   │    │
│  │   • payload: {...}                                           │    │
│  │   • securityContext: "eyJhbGciOiJSUzI1NiIs..."  ◄── JWT      │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                               │                                      │
│                               ▼                                      │
│                      Message Broker (Kafka)                          │
└─────────────────────────────────────────────────────────────────────┘

                               │
                               ▼

┌─────────────────────────────────────────────────────────────────────┐
│                    Step Execution (Consumer Thread)                  │
│                               │                                      │
│                               ▼                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │       SecurityContextPropagator.restore(securityContext)     │    │
│  │       Sets: UserContext, CompanyContext, etc.                 │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                               │                                      │
│                               ▼                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │                     @Step method execution                    │    │
│  │       UserContextHolder.get() → UserContext available        │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                               │                                      │
│                               ▼                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │              SecurityContextPropagator.clear()               │    │
│  │              Cleans up thread-local context                   │    │
│  └─────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
```

## SecurityContextPropagator Interface

The `SecurityContextPropagator` interface defines the contract for security context handling:

```java
public interface SecurityContextPropagator {

    /**
     * Capture the current security context.
     * Called when starting a workflow to capture the caller's authentication.
     *
     * @return the serialized security context (e.g., JWT token), or null if none
     */
    String capture();

    /**
     * Restore the security context before executing a workflow step.
     * Sets up the security context so the step method can access the authenticated principal.
     *
     * @param securityContext the serialized security context to restore
     */
    void restore(String securityContext);

    /**
     * Clear the security context after step execution.
     * Cleans up thread-local state to prevent security context leakage.
     */
    void clear();

    /**
     * Check if security propagation is enabled.
     *
     * @return true if security context should be propagated
     */
    boolean isEnabled();
}
```

## Default Implementation

By default, Steppr Flow uses `NoOpSecurityContextPropagator` which does nothing:

```java
public class NoOpSecurityContextPropagator implements SecurityContextPropagator {

    @Override
    public String capture() {
        return null;
    }

    @Override
    public void restore(String securityContext) {
        // No-op
    }

    @Override
    public void clear() {
        // No-op
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
```

## Custom Implementation (JWT Example)

To enable security context propagation, provide your own `SecurityContextPropagator` bean. Here's an example for JWT-based authentication:

```java
@Component
@Slf4j
public class JwtSecurityContextPropagator implements SecurityContextPropagator {

    @Override
    public String capture() {
        // Get user context from current thread (set by security filter)
        UserContext userContext = UserContextHolder.get();
        if (userContext == null) {
            log.warn("No user context available to capture");
            return null;
        }
        return userContext.getAccessToken();
    }

    @Override
    public void restore(String securityContext) {
        if (securityContext == null || securityContext.isBlank()) {
            return;
        }

        try {
            // Parse JWT token (without validation - already validated at start)
            SignedJWT signedJWT = SignedJWT.parse(securityContext);
            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

            // Extract and set user context
            UserContext userContext = UserContext.builder()
                    .username(claims.getStringClaim("preferred_username"))
                    .email(claims.getStringClaim("email"))
                    .firstName(claims.getStringClaim("given_name"))
                    .lastName(claims.getStringClaim("family_name"))
                    .authorities(extractRoles(claims))
                    .company(claims.getStringClaim("company"))
                    .accessToken(securityContext)
                    .build();
            UserContextHolder.set(userContext);

            // Set company context
            String company = claims.getStringClaim("company");
            if (company != null) {
                CompanyContextHolder.setCompany(company);
            }

            log.debug("Security context restored for user: {}", userContext.getUsername());
        } catch (ParseException e) {
            // A malformed context is a terminal failure: rethrow so stepprflow
            // sends the message to the DLQ instead of running the step
            // unauthenticated (see "Failure Handling"). Never swallow-and-continue.
            throw new IllegalStateException("Unparseable security context", e);
        }
    }

    @Override
    public void clear() {
        UserContextHolder.clear();
        CompanyContextHolder.clear();
        EstablishmentsContextHolder.clear();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractRoles(JWTClaimsSet claims) {
        Object realmAccess = claims.getClaim("realm_access");
        if (realmAccess instanceof Map) {
            Map<String, Object> realmAccessMap = (Map<String, Object>) realmAccess;
            Object roles = realmAccessMap.get("roles");
            if (roles instanceof List) {
                return new HashSet<>((List<String>) roles);
            }
        }
        return null;
    }
}
```

## Using Security Context in Steps

Once configured, you can access the security context in your workflow steps:

```java
@Component
@Topic("order-workflow")
public class OrderWorkflow {

    @Step(id = 1, label = "Validate Order")
    public OrderPayload validate(OrderPayload payload) {
        // Access the authenticated user
        UserContext user = UserContextHolder.get();

        log.info("Processing order for user: {}", user.getUsername());

        // Check permissions
        if (!user.hasRole("ORDER_CREATE")) {
            throw new AccessDeniedException("User cannot create orders");
        }

        // Get tenant/company context
        String company = CompanyContextHolder.getCompany();
        payload.setCompany(company);

        return payload;
    }

    @Step(id = 2, label = "Process Payment")
    public OrderPayload payment(OrderPayload payload) {
        // Security context is automatically available here too
        UserContext user = UserContextHolder.get();
        log.info("Processing payment for: {}", user.getEmail());

        return payload;
    }
}
```

## Spring Security Integration

If you're using Spring Security with OAuth2/JWT, your security filter should populate the `UserContextHolder` before the workflow is started:

```java
@Component
public class SecurityContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();

            if (auth instanceof JwtAuthenticationToken jwtAuth) {
                Jwt jwt = jwtAuth.getToken();

                UserContext userContext = UserContext.builder()
                        .username(jwt.getClaimAsString("preferred_username"))
                        .email(jwt.getClaimAsString("email"))
                        .accessToken(jwt.getTokenValue())
                        // ... other claims
                        .build();

                UserContextHolder.set(userContext);
            }

            filterChain.doFilter(request, response);
        } finally {
            UserContextHolder.clear();
        }
    }
}
```

## Context Integrity (HMAC Signing)

The security context travels inside the workflow message across the broker. Left
unprotected, a crafted message could **forge an identity**. Steppr Flow signs the
context envelope with an HMAC and verifies it before restoring (ADR-0003, SF-3).

Enable it by configuring a **shared secret** on every service that participates
in the same workflows:

```yaml
stepprflow:
  security:
    context-signing:
      secret: ${STEPPRFLOW_CONTEXT_SIGNING_SECRET}   # same value on all services
```

- The captured context is wrapped as `<signature>.<rawContext>`, where the HMAC
  (HMAC-SHA256) is **bound to the `executionId` and `topic`** — a signature
  cannot be replayed onto a different execution or topic.
- On restore, the signature is verified **before** the context is handed to your
  `SecurityContextPropagator`. A missing or mismatched signature raises
  `ForgedSecurityContextException` and the message is rejected (see Failure
  Handling below).
- When no secret is set, signing is **disabled**: the context travels unprotected
  and a one-time warning is logged. Set a secret in any environment where the
  broker is shared or reachable beyond the trusted services.
- The raw context value (the JWT) is **never logged** — only its presence.

## Failure Handling

Two hardened guarantees apply to restore/verify (ADR-0003, SF-1):

- **Always cleared, never leaked.** The context is captured on the *caller's*
  thread (never an async worker), restored inside a `try/finally` in the step
  executor, and **cleared in the `finally`** — including on the bounded timeout
  worker — so it can never outlive one step execution on a pooled consumer
  thread.
- **Restore/verify failure is terminal and non-retryable.** If verification or
  your `restore()` throws, the message goes **straight to the DLQ** and is
  **not redelivered**: the credential is embedded in the message, so a retry
  would fail identically and only poison-pill the consumer.

Because a restore failure is terminal, your `restore()` must **not silently
swallow** a malformed context and continue (that would run the step
unauthenticated). Let the exception propagate so the framework can DLQ it.

## Configuration

The security context propagator is automatically injected via Spring's dependency injection. Your custom implementation takes precedence over the default `NoOpSecurityContextPropagator` due to `@ConditionalOnMissingBean`:

```java
// In StepprFlowAutoConfiguration
@Bean
@ConditionalOnMissingBean(SecurityContextPropagator.class)
public SecurityContextPropagator securityContextPropagator() {
    return new NoOpSecurityContextPropagator();
}
```

## Troubleshooting

### Security context not captured

If `capture()` returns `null`, check that:
1. The HTTP request has a valid JWT token
2. Your security filter populates `UserContextHolder` before the controller is called
3. `UserContext.accessToken` is set with the raw JWT token

### Security context not restored

If `restore()` doesn't set the context:
1. Check that `WorkflowMessage.securityContext` is not null (check logs)
2. Verify the JWT token is valid and parseable
3. Check for `ParseException` in logs

Note: a restore failure is **terminal** — the message is sent to the DLQ and not
retried (see Failure Handling). If executions are landing in the DLQ right after
start, inspect the DLQ'd message and your `restore()` logic.

### Message rejected / sent to the DLQ with `ForgedSecurityContextException`

The context's HMAC signature failed verification. Check that:
1. **Every** participating service sets the *same*
   `stepprflow.security.context-signing.secret` (a mismatch rejects every
   context).
2. The secret is actually loaded (env var resolved, not blank).
3. The message was not replayed from a different execution/topic (the signature
   is bound to `executionId`+`topic`).

### Enable debug logging

```yaml
logging:
  level:
    io.github.stepprflow.core.service: DEBUG
    com.yourapp.security: DEBUG
```

This will show:
- Which `SecurityContextPropagator` implementation is being used
- Whether security context is captured/restored
- Any parsing errors

## Best Practices

1. **Don't validate JWT on restore**: The token was already validated when the workflow was started. Re-validating adds latency and may fail if the token expired during processing.

2. **Clear context in finally block**: Always clear the security context after step execution to prevent leakage to other requests.

3. **Handle missing context gracefully**: Steps should handle the case where security context is not available (e.g., for system-triggered workflows).

4. **Log security events**: Log when security context is captured/restored for audit purposes. Never log the raw token — log its presence only.

5. **Enable context signing in shared environments**: Set
   `stepprflow.security.context-signing.secret` (the same value on every
   participating service) so a crafted message cannot forge an identity. Treat
   the secret like any credential (Vault / env var, not committed).

6. **Let restore failures be terminal**: Don't swallow a malformed or
   unverifiable context in `restore()` — rethrow so the framework DLQs the
   message instead of running the step unauthenticated.

5. **Consider token expiration**: For long-running workflows, the JWT token may expire. Consider storing essential claims rather than the full token, or implement token refresh logic.
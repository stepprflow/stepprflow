package io.github.stepprflow.monitor.controller;

import io.github.stepprflow.monitor.MonitorProperties;
import io.github.stepprflow.monitor.security.MonitorSecurityConfig;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the authentication mode (so the UI can render the right login flow)
 * and the current user's identity and authorities (SF-5).
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final MonitorProperties properties;

    /**
     * Public endpoint: lets the UI decide between a local login form (basic) and
     * an OIDC redirect without needing to be authenticated first.
     *
     * @return the configured auth mode
     */
    @GetMapping("/config")
    public Map<String, Object> config() {
        final String mode = properties.getAuth().getMode();
        return Map.of(
            "mode", mode == null ? "" : mode.toLowerCase(),
            "oidc", "oidc".equalsIgnoreCase(mode));
    }

    /**
     * Authenticated endpoint: identity + authorities of the current user, used by
     * the UI for route guards and role-based control masking.
     *
     * @param authentication the current authentication (null if anonymous)
     * @return the user info, or 401 if not authenticated
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(final Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return ResponseEntity.status(401).build();
        }
        final List<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        return ResponseEntity.ok(Map.of(
            "username", authentication.getName(),
            "authorities", authorities,
            "operator", authorities.contains(MonitorSecurityConfig.OPERATOR)));
    }
}

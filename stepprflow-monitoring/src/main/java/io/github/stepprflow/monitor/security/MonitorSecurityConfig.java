package io.github.stepprflow.monitor.security;

import io.github.stepprflow.monitor.MonitorProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Dual-mode security for the monitoring API, dashboard and WebSocket (SF-5).
 *
 * <p>Before this, every endpoint — including the mutating ones (circuit-breaker
 * reset, retry, outbox) and the WebSocket — was open. The mode is selected by
 * {@code stepprflow.monitor.auth.mode}:</p>
 * <ul>
 *   <li>{@code basic}: form / HTTP-Basic login against a configured local user;</li>
 *   <li>{@code oidc}: server-side OAuth2 login against a Keycloak/OIDC provider
 *       (standard {@code spring.security.oauth2.client.*} config), with KC roles
 *       mapped to the VIEWER / OPERATOR authorities.</li>
 * </ul>
 * <p>If the mode is unset the chain denies every request (fail-closed): the
 * monitoring endpoints are never served unauthenticated.</p>
 *
 * <p>Authorization: read requests require authentication (VIEWER or OPERATOR);
 * mutating requests (POST/PUT/DELETE/PATCH under {@code /api}) require OPERATOR.</p>
 */
@Configuration
@EnableWebSecurity
@Slf4j
public class MonitorSecurityConfig {

    /** Authority allowed to perform mutating actions. */
    public static final String OPERATOR = "OPERATOR";

    /** Authority allowed read-only access. */
    public static final String VIEWER = "VIEWER";

    /** Publicly reachable paths (UI shell, login, auth-mode probe, health). */
    private static final String[] PUBLIC_PATHS = {
        "/api/auth/config",
        "/actuator/health", "/actuator/health/**", "/actuator/info",
        "/login", "/error", "/favicon.ico",
        "/", "/index.html", "/assets/**", "/static/**", "/css/**", "/js/**", "/img/**"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(
            final HttpSecurity http, final MonitorProperties properties)
            throws Exception {

        final String mode = properties.getAuth().getMode();
        final boolean basic = "basic".equalsIgnoreCase(mode);
        final boolean oidc = "oidc".equalsIgnoreCase(mode);

        if (!basic && !oidc) {
            // Fail-closed: a misconfigured deployment must not expose anything.
            log.error("stepprflow.monitor.auth.mode is not 'basic' or 'oidc' "
                    + "(value: '{}') — denying ALL requests to the monitoring "
                    + "endpoints until it is configured.", mode);
            http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(a -> a
                    // Liveness/health stays reachable even when fail-closed, so
                    // container HEALTHCHECKs and orchestrator probes can tell
                    // "up but unconfigured" from "down". Everything else is denied.
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .anyRequest().denyAll());
            return http.build();
        }

        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(PUBLIC_PATHS).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/**").hasAuthority(OPERATOR)
                .requestMatchers(HttpMethod.PUT, "/api/**").hasAuthority(OPERATOR)
                .requestMatchers(HttpMethod.DELETE, "/api/**").hasAuthority(OPERATOR)
                .requestMatchers(HttpMethod.PATCH, "/api/**").hasAuthority(OPERATOR)
                // Reads require an explicit monitoring role, not merely being
                // authenticated: in OIDC mode a realm account with no stepprflow
                // role must not be able to read executions/payloads/metrics.
                .requestMatchers("/api/**", "/ws/**").hasAnyAuthority(VIEWER, OPERATOR)
                .anyRequest().authenticated())
            // SPA-friendly CSRF: non-HttpOnly cookie the UI echoes as X-XSRF-TOKEN.
            // The WebSocket handshake carries no CSRF token (authz is by session).
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers("/ws/**"))
            // Force the deferred CSRF token to load so the XSRF-TOKEN cookie is
            // actually written: a pure SPA never reads the token server-side, so
            // without this the cookie is never set and the form login and every
            // mutation would be rejected with 403.
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            // XHR under /api gets a 401 instead of a login redirect; browser
            // navigations still redirect to the login entry point.
            .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                new AntPathRequestMatcher("/api/**")));

        if (oidc) {
            http.oauth2Login(login -> login.userInfoEndpoint(
                ui -> ui.userAuthoritiesMapper(oidcAuthoritiesMapper(properties))));
            http.logout(logout -> logout.logoutSuccessUrl("/"));
        } else {
            http.formLogin(Customizer.withDefaults());
            http.httpBasic(Customizer.withDefaults());
            http.logout(logout -> logout.logoutSuccessUrl("/"));
        }

        return http.build();
    }

    /**
     * Local user for basic mode. Created only when the mode is basic.
     *
     * @param properties the monitor properties
     * @param encoder the password encoder
     * @return an in-memory user details service holding the configured user
     */
    @Bean
    public UserDetailsService monitorUserDetailsService(
            final MonitorProperties properties, final PasswordEncoder encoder) {
        final MonitorProperties.Basic b = properties.getAuth().getBasic();
        if (!"basic".equalsIgnoreCase(properties.getAuth().getMode())) {
            // Not basic mode: no local user. Return an empty manager so the bean
            // exists without granting access.
            return new InMemoryUserDetailsManager();
        }
        final String raw = b.getPassword();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("stepprflow.monitor.auth.mode=basic "
                    + "requires stepprflow.monitor.auth.basic.password");
        }
        final String encoded;
        if (raw.startsWith("{")) {
            encoded = raw; // already a {id}hash, e.g. {bcrypt}$2a$...
        } else {
            log.warn("stepprflow.monitor.auth.basic.password is plaintext; "
                    + "prefer a bcrypt hash ({bcrypt}$2a$...). Encoding it now.");
            encoded = encoder.encode(raw);
        }
        final UserDetails user = User.withUsername(b.getUsername())
                .password(encoded)
                .authorities(new SimpleGrantedAuthority(normalizeRole(b.getRole())))
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    private GrantedAuthoritiesMapper oidcAuthoritiesMapper(
            final MonitorProperties properties) {
        final MonitorProperties.Oidc oidc = properties.getAuth().getOidc();
        return (authorities) -> {
            final Collection<GrantedAuthority> mapped = new ArrayList<>();
            for (GrantedAuthority authority : authorities) {
                if (authority instanceof OidcUserAuthority oidcAuth) {
                    final Object claim =
                        oidcAuth.getAttributes().get(oidc.getRolesClaim());
                    final List<String> roles = asStringList(claim);
                    if (roles.contains(oidc.getOperatorRole())) {
                        mapped.add(new SimpleGrantedAuthority(OPERATOR));
                    }
                    if (roles.contains(oidc.getViewerRole())) {
                        mapped.add(new SimpleGrantedAuthority(VIEWER));
                    }
                }
            }
            return mapped;
        };
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(final Object claim) {
        final List<String> out = new ArrayList<>();
        if (claim instanceof Collection<?> c) {
            for (Object o : c) {
                if (o != null) {
                    out.add(o.toString());
                }
            }
        }
        return out;
    }

    private static String normalizeRole(final String role) {
        return VIEWER.equalsIgnoreCase(role) ? VIEWER : OPERATOR;
    }

    /**
     * Forces the deferred {@link CsrfToken} to be resolved on every request so
     * the {@code XSRF-TOKEN} cookie is written for the SPA to read.
     */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(final HttpServletRequest request,
                final HttpServletResponse response, final FilterChain filterChain)
                throws ServletException, IOException {
            final CsrfToken csrfToken =
                    (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                // Accessing the token value triggers the repository to persist it
                // (write the cookie).
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}

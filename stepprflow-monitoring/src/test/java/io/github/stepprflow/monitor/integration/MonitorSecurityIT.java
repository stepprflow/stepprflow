package io.github.stepprflow.monitor.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SF-5: verifies the monitoring authorization model — public auth-config,
 * authenticated reads, and OPERATOR-only mutations. The test profile runs in
 * basic mode; {@code @WithMockUser} supplies the authorities.
 */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DisplayName("Monitoring security (SF-5)")
class MonitorSecurityIT extends MongoDBTestContainerConfig {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/auth/config is public")
    void authConfigIsPublic() throws Exception {
        mockMvc.perform(get("/api/auth/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("basic"));
    }

    @Test
    @DisplayName("The XSRF-TOKEN cookie is emitted so the SPA can send it back (B1)")
    void csrfCookieIsEmitted() throws Exception {
        // Without the CsrfCookieFilter forcing the deferred token to load, the
        // cookie would never be written and login + every mutation would 403.
        mockMvc.perform(get("/api/auth/config"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    @DisplayName("GET /api/auth/me is 401 when unauthenticated")
    void meRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "viewer", authorities = {"VIEWER"})
    @DisplayName("VIEWER can read but cannot mutate")
    void viewerReadOnly() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operator").value(false));

        mockMvc.perform(post("/api/circuit-breakers/some-cb/reset").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "operator", authorities = {"OPERATOR"})
    @DisplayName("OPERATOR can mutate")
    void operatorCanMutate() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operator").value(true));

        // Authorized (not 401/403); the exact 2xx/4xx depends on the service,
        // what matters for SF-5 is that OPERATOR is not blocked.
        int statusCode = mockMvc.perform(post("/api/circuit-breakers/some-cb/reset").with(csrf()))
                .andReturn().getResponse().getStatus();
        assertThat(statusCode).isNotIn(401, 403);
    }

    @Test
    @DisplayName("Root static assets referenced by the UI shell are public (logo, favicon)")
    void rootStaticAssetsArePublic() throws Exception {
        // The login page renders <img src="/stepprflow-logo.png"> and the favicon
        // before the user authenticates. If these are not public the browser's
        // request for them is rejected, the image is broken on the login screen,
        // and — worse — that rejected request becomes Spring Security's saved
        // request, so the post-login redirect lands on the image instead of the
        // dashboard. They must never require authentication.
        for (final String path : new String[] {"/stepprflow-logo.png", "/favicon.svg"}) {
            final int statusCode = mockMvc.perform(get(path))
                    .andReturn().getResponse().getStatus();
            assertThat(statusCode)
                    .as("%s must be publicly reachable (not 401 Unauthorized)", path)
                    .isNotEqualTo(401);
        }
    }
}

package io.github.stepprflow.monitor.integration;

import io.github.stepprflow.monitor.model.RegisteredWorkflow;
import io.github.stepprflow.monitor.repository.RegisteredWorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("Registry Controller Integration Tests")
class RegistryControllerIT extends MongoDBTestContainerConfig {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisteredWorkflowRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Nested
    @DisplayName("DELETE /api/registry/workflows/{workflowId}")
    class PurgeSingleWorkflowTests {

        @Test
        @DisplayName("Should return 204 and delete INACTIVE workflow")
        void shouldReturn204AndDeleteInactiveWorkflow() throws Exception {
            RegisteredWorkflow workflow = repository.save(RegisteredWorkflow.builder()
                    .topic("order-workflow")
                    .serviceName("order-service")
                    .status(RegisteredWorkflow.Status.INACTIVE)
                    .registeredBy(new HashSet<>())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());

            mockMvc.perform(delete("/api/registry/workflows/{id}", workflow.getId()))
                    .andExpect(status().isNoContent());

            assertThat(repository.findById(workflow.getId())).isEmpty();
        }

        @Test
        @DisplayName("Should return 409 and preserve ACTIVE workflow")
        void shouldReturn409AndPreserveActiveWorkflow() throws Exception {
            RegisteredWorkflow workflow = repository.save(RegisteredWorkflow.builder()
                    .topic("order-workflow")
                    .serviceName("order-service")
                    .status(RegisteredWorkflow.Status.ACTIVE)
                    .registeredBy(new HashSet<>())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());

            mockMvc.perform(delete("/api/registry/workflows/{id}", workflow.getId()))
                    .andExpect(status().isConflict());

            assertThat(repository.findById(workflow.getId())).isPresent();
        }

        @Test
        @DisplayName("Should return 404 for non-existent workflow")
        void shouldReturn404ForNonExistentWorkflow() throws Exception {
            mockMvc.perform(delete("/api/registry/workflows/{id}", "non-existent-id"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("DELETE /api/registry/workflows/inactive")
    class PurgeAllInactiveWorkflowsTests {

        @Test
        @DisplayName("Should purge all inactive and preserve active workflows")
        void shouldPurgeAllInactiveAndPreserveActive() throws Exception {
            RegisteredWorkflow inactive1 = repository.save(RegisteredWorkflow.builder()
                    .topic("inactive-1")
                    .serviceName("service-a")
                    .status(RegisteredWorkflow.Status.INACTIVE)
                    .registeredBy(new HashSet<>())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());

            RegisteredWorkflow inactive2 = repository.save(RegisteredWorkflow.builder()
                    .topic("inactive-2")
                    .serviceName("service-b")
                    .status(RegisteredWorkflow.Status.INACTIVE)
                    .registeredBy(new HashSet<>())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());

            RegisteredWorkflow active = repository.save(RegisteredWorkflow.builder()
                    .topic("active-1")
                    .serviceName("service-c")
                    .status(RegisteredWorkflow.Status.ACTIVE)
                    .registeredBy(new HashSet<>())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());

            mockMvc.perform(delete("/api/registry/workflows/inactive"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.purgedCount").value(2));

            assertThat(repository.findById(inactive1.getId())).isEmpty();
            assertThat(repository.findById(inactive2.getId())).isEmpty();
            assertThat(repository.findById(active.getId())).isPresent();
        }

        @Test
        @DisplayName("Should return 0 when no inactive workflows exist")
        void shouldReturnZeroWhenNoInactive() throws Exception {
            repository.save(RegisteredWorkflow.builder()
                    .topic("active-1")
                    .serviceName("service-a")
                    .status(RegisteredWorkflow.Status.ACTIVE)
                    .registeredBy(new HashSet<>())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());

            mockMvc.perform(delete("/api/registry/workflows/inactive"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.purgedCount").value(0));
        }
    }
}

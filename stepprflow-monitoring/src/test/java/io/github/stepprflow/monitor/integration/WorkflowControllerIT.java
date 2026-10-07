package io.github.stepprflow.monitor.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stepprflow.core.model.RetryInfo;
import io.github.stepprflow.core.model.WorkflowStatus;
import io.github.stepprflow.monitor.model.WorkflowExecution;
import io.github.stepprflow.monitor.repository.WorkflowExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@WithMockUser(authorities = {"OPERATOR"})
@DisplayName("Workflow Controller Integration Tests")
class WorkflowControllerIT extends MongoDBTestContainerConfig {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkflowExecutionRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Nested
    @DisplayName("GET /api/workflows/{executionId}")
    class GetExecutionTests {

        @Test
        @DisplayName("Should return execution when found")
        void shouldReturnExecutionWhenFound() throws Exception {
            // Given
            var executionId = UUID.randomUUID().toString();
            var execution = createExecution(executionId, "order-workflow", WorkflowStatus.IN_PROGRESS);
            repository.save(execution);

            // When & Then
            mockMvc.perform(get("/api/workflows/{executionId}", executionId)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.executionId").value(executionId))
                    .andExpect(jsonPath("$.topic").value("order-workflow"))
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.currentStep").value(1))
                    .andExpect(jsonPath("$.totalSteps").value(3));
        }

        @Test
        @DisplayName("Should return 404 when execution not found")
        void shouldReturn404WhenNotFound() throws Exception {
            // Given
            var unknownId = UUID.randomUUID().toString();

            // When & Then
            mockMvc.perform(get("/api/workflows/{executionId}", unknownId)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return execution with full details")
        void shouldReturnExecutionWithFullDetails() throws Exception {
            // Given
            var executionId = UUID.randomUUID().toString();
            var execution = WorkflowExecution.builder()
                    .executionId(executionId)
                    .correlationId(UUID.randomUUID().toString())
                    .topic("detailed-workflow")
                    .status(WorkflowStatus.FAILED)
                    .currentStep(2)
                    .totalSteps(5)
                    .payload(Map.of("key", "value"))
                    .metadata(Map.of("source", "api"))
                    .retryInfo(RetryInfo.builder().attempt(2).maxAttempts(3).build())
                    .createdAt(Instant.now())
                    .build();
            repository.save(execution);

            // When & Then
            mockMvc.perform(get("/api/workflows/{executionId}", executionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.correlationId").isNotEmpty())
                    .andExpect(jsonPath("$.payload.key").value("value"))
                    .andExpect(jsonPath("$.metadata.source").value("api"))
                    .andExpect(jsonPath("$.retryInfo.attempt").value(2))
                    .andExpect(jsonPath("$.retryInfo.maxAttempts").value(3));
        }
    }

    @Nested
    @DisplayName("GET /api/workflows")
    class ListExecutionsTests {

        @BeforeEach
        void cleanUp() {
            repository.deleteAll();
        }

        @Test
        @DisplayName("Should return paginated executions")
        void shouldReturnPaginatedExecutions() throws Exception {
            // Given
            var executions = IntStream.rangeClosed(1, 25)
                    .mapToObj(i -> createExecution("exec-" + i, "topic-" + (i % 3), WorkflowStatus.PENDING))
                    .toList();
            repository.saveAll(executions);

            // When & Then
            mockMvc.perform(get("/api/workflows")
                            .param("page", "0")
                            .param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(10)))
                    .andExpect(jsonPath("$.totalElements").value(25))
                    .andExpect(jsonPath("$.totalPages").value(3))
                    .andExpect(jsonPath("$.number").value(0));
        }

        @Test
        @DisplayName("Should filter by topic")
        void shouldFilterByTopic() throws Exception {
            // Given
            repository.saveAll(List.of(
                    createExecution("e1", "order-workflow", WorkflowStatus.PENDING),
                    createExecution("e2", "order-workflow", WorkflowStatus.COMPLETED),
                    createExecution("e3", "payment-workflow", WorkflowStatus.PENDING)
            ));

            // When & Then
            mockMvc.perform(get("/api/workflows")
                            .param("topic", "order-workflow"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(2)))
                    .andExpect(jsonPath("$.content[*].topic", everyItem(is("order-workflow"))));
        }

        @Test
        @DisplayName("Should filter by status")
        void shouldFilterByStatus() throws Exception {
            // Given
            repository.saveAll(List.of(
                    createExecution("e1", "topic-1", WorkflowStatus.PENDING),
                    createExecution("e2", "topic-2", WorkflowStatus.COMPLETED),
                    createExecution("e3", "topic-3", WorkflowStatus.COMPLETED),
                    createExecution("e4", "topic-4", WorkflowStatus.FAILED)
            ));

            // When & Then
            mockMvc.perform(get("/api/workflows")
                            .param("status", "COMPLETED"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(2)))
                    .andExpect(jsonPath("$.content[*].status", everyItem(is("COMPLETED"))));
        }

        @Test
        @DisplayName("Should filter by topic and status")
        void shouldFilterByTopicAndStatus() throws Exception {
            // Given
            repository.saveAll(List.of(
                    createExecution("e1", "order-workflow", WorkflowStatus.PENDING),
                    createExecution("e2", "order-workflow", WorkflowStatus.COMPLETED),
                    createExecution("e3", "payment-workflow", WorkflowStatus.PENDING)
            ));

            // When & Then
            mockMvc.perform(get("/api/workflows")
                            .param("topic", "order-workflow")
                            .param("status", "PENDING"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.content[0].executionId").value("e1"));
        }

        @Test
        @DisplayName("Should sort by createdAt descending by default")
        void shouldSortByCreatedAtDescending() throws Exception {
            // Given
            var now = Instant.now();
            repository.saveAll(List.of(
                    createExecutionWithTimestamp("oldest", "topic", now.minus(3, ChronoUnit.HOURS)),
                    createExecutionWithTimestamp("newest", "topic", now),
                    createExecutionWithTimestamp("middle", "topic", now.minus(1, ChronoUnit.HOURS))
            ));

            // When & Then
            mockMvc.perform(get("/api/workflows")
                            .param("sort", "createdAt")
                            .param("direction", "DESC"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].executionId").value("newest"))
                    .andExpect(jsonPath("$.content[1].executionId").value("middle"))
                    .andExpect(jsonPath("$.content[2].executionId").value("oldest"));
        }
    }

    @Nested
    @DisplayName("GET /api/workflows/recent")
    class GetRecentExecutionsTests {

        @Test
        @DisplayName("Should return recent executions")
        void shouldReturnRecentExecutions() throws Exception {
            // Given
            var now = Instant.now();
            var executions = IntStream.rangeClosed(1, 15)
                    .mapToObj(i -> createExecutionWithTimestamp(
                            "exec-" + i,
                            "topic",
                            now.minus(i, ChronoUnit.MINUTES)
                    ))
                    .toList();
            repository.saveAll(executions);

            // When & Then
            mockMvc.perform(get("/api/workflows/recent"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(10)))
                    .andExpect(jsonPath("$[0].executionId").value("exec-1"))
                    .andExpect(jsonPath("$[9].executionId").value("exec-10"));
        }

        @Test
        @DisplayName("Should return empty list when no executions")
        void shouldReturnEmptyListWhenNoExecutions() throws Exception {
            // Given - empty repository

            // When & Then
            mockMvc.perform(get("/api/workflows/recent"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    @Nested
    @DisplayName("GET /api/workflows/stats")
    class GetStatisticsTests {

        @Test
        @DisplayName("Should return statistics for all statuses")
        void shouldReturnStatisticsForAllStatuses() throws Exception {
            // Given
            repository.saveAll(List.of(
                    createExecution("e1", "t1", WorkflowStatus.PENDING),
                    createExecution("e2", "t2", WorkflowStatus.PENDING),
                    createExecution("e3", "t3", WorkflowStatus.IN_PROGRESS),
                    createExecution("e4", "t4", WorkflowStatus.COMPLETED),
                    createExecution("e5", "t5", WorkflowStatus.COMPLETED),
                    createExecution("e6", "t6", WorkflowStatus.COMPLETED),
                    createExecution("e7", "t7", WorkflowStatus.FAILED)
            ));

            // When & Then
            mockMvc.perform(get("/api/workflows/stats"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pending").value(2))
                    .andExpect(jsonPath("$.inProgress").value(1))
                    .andExpect(jsonPath("$.completed").value(3))
                    .andExpect(jsonPath("$.failed").value(1))
                    .andExpect(jsonPath("$.total").value(7));
        }

        @Test
        @DisplayName("Should return zero counts when no executions")
        void shouldReturnZeroCountsWhenNoExecutions() throws Exception {
            // Given - empty repository

            // When & Then
            mockMvc.perform(get("/api/workflows/stats"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pending").value(0))
                    .andExpect(jsonPath("$.completed").value(0))
                    .andExpect(jsonPath("$.total").value(0));
        }
    }

    @Nested
    @DisplayName("POST /api/workflows/{executionId}/resume")
    class ResumeWorkflowTests {

        @Test
        @DisplayName("Should resume failed workflow")
        void shouldResumeFailedWorkflow() throws Exception {
            // Given
            var executionId = UUID.randomUUID().toString();
            var execution = createExecution(executionId, "resumable-workflow", WorkflowStatus.FAILED);
            repository.save(execution);

            // When & Then
            mockMvc.perform(post("/api/workflows/{executionId}/resume", executionId))
                    .andExpect(status().isAccepted());
        }

        @Test
        @DisplayName("Should resume from specific step")
        void shouldResumeFromSpecificStep() throws Exception {
            // Given
            var executionId = UUID.randomUUID().toString();
            var execution = createExecution(executionId, "workflow", WorkflowStatus.FAILED);
            execution.setCurrentStep(3);
            repository.save(execution);

            // When & Then
            mockMvc.perform(post("/api/workflows/{executionId}/resume", executionId)
                            .param("fromStep", "1"))
                    .andExpect(status().isAccepted());
        }

        @Test
        @DisplayName("Should return error when execution not found")
        void shouldReturnErrorWhenExecutionNotFound() throws Exception {
            // Given
            var unknownId = UUID.randomUUID().toString();

            // When & Then
            mockMvc.perform(post("/api/workflows/{executionId}/resume", unknownId))
                    .andExpect(status().is4xxClientError());
        }
    }

    @Nested
    @DisplayName("DELETE /api/workflows/{executionId}")
    class CancelWorkflowTests {

        @Test
        @DisplayName("Should cancel running workflow")
        void shouldCancelRunningWorkflow() throws Exception {
            // Given
            var executionId = UUID.randomUUID().toString();
            var execution = createExecution(executionId, "cancellable-workflow", WorkflowStatus.IN_PROGRESS);
            repository.save(execution);

            // When & Then
            mockMvc.perform(delete("/api/workflows/{executionId}", executionId))
                    .andExpect(status().isNoContent());

            // Verify
            var cancelled = repository.findById(executionId).orElseThrow();
            org.assertj.core.api.Assertions.assertThat(cancelled.getStatus()).isEqualTo(WorkflowStatus.CANCELLED);
        }

        @Test
        @DisplayName("Should return error when trying to cancel completed workflow")
        void shouldReturnErrorWhenCancellingCompletedWorkflow() throws Exception {
            // Given
            var executionId = UUID.randomUUID().toString();
            var execution = createExecution(executionId, "completed-workflow", WorkflowStatus.COMPLETED);
            repository.save(execution);

            // When & Then
            mockMvc.perform(delete("/api/workflows/{executionId}", executionId))
                    .andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("Should return error when execution not found")
        void shouldReturnErrorWhenNotFound() throws Exception {
            // Given
            var unknownId = UUID.randomUUID().toString();

            // When & Then
            mockMvc.perform(delete("/api/workflows/{executionId}", unknownId))
                    .andExpect(status().is4xxClientError());
        }
    }

    // Helper methods
    private WorkflowExecution createExecution(String executionId, String topic, WorkflowStatus status) {
        return WorkflowExecution.builder()
                .executionId(executionId)
                .correlationId(UUID.randomUUID().toString())
                .topic(topic)
                .status(status)
                .currentStep(1)
                .totalSteps(3)
                .createdAt(Instant.now())
                .build();
    }

    private WorkflowExecution createExecutionWithTimestamp(String executionId, String topic, Instant createdAt) {
        return WorkflowExecution.builder()
                .executionId(executionId)
                .topic(topic)
                .status(WorkflowStatus.PENDING)
                .currentStep(1)
                .totalSteps(1)
                .createdAt(createdAt)
                .build();
    }
}

package io.github.stepprflow.monitor.integration;

import io.github.stepprflow.core.model.RetryInfo;
import io.github.stepprflow.core.model.WorkflowStatus;
import io.github.stepprflow.monitor.model.WorkflowExecution;
import io.github.stepprflow.monitor.repository.WorkflowExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@DataMongoTest
@Testcontainers
@EnableMongoRepositories(basePackages = "io.github.stepprflow.monitor.repository")
@DisplayName("Workflow Monitor MongoDB Integration Tests")
class WorkflowMonitorIntegrationTest {

    @Container
    static MongoDBContainer mongodb = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongodb::getReplicaSetUrl);
        registry.add("spring.data.mongodb.database", () -> "stepprflow-test");
        // Disable Flapdoodle embedded MongoDB (we use Testcontainers)
        registry.add("spring.autoconfigure.exclude", () -> "de.flapdoodle.embed.mongo.spring.autoconfigure.EmbeddedMongoAutoConfiguration");
    }

    @Autowired
    private WorkflowExecutionRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Nested
    @DisplayName("Basic CRUD operations")
    class CrudOperationsTests {

        @Test
        @DisplayName("Should save and retrieve workflow execution")
        void shouldSaveAndRetrieveWorkflowExecution() {
            // Given
            var execution = WorkflowExecution.builder()
                    .executionId(UUID.randomUUID().toString())
                    .correlationId(UUID.randomUUID().toString())
                    .topic("order-workflow")
                    .status(WorkflowStatus.IN_PROGRESS)
                    .currentStep(2)
                    .totalSteps(5)
                    .payload(Map.of("orderId", "ORD-123"))
                    .createdAt(Instant.now())
                    .build();

            // When
            var saved = repository.save(execution);
            var found = repository.findById(saved.getExecutionId());

            // Then
            assertThat(found)
                    .isPresent()
                    .get()
                    .extracting(
                            WorkflowExecution::getTopic,
                            WorkflowExecution::getStatus,
                            WorkflowExecution::getCurrentStep,
                            WorkflowExecution::getTotalSteps
                    )
                    .containsExactly("order-workflow", WorkflowStatus.IN_PROGRESS, 2, 5);
        }

        @Test
        @DisplayName("Should update workflow execution status")
        void shouldUpdateWorkflowExecutionStatus() {
            // Given
            var executionId = UUID.randomUUID().toString();
            var execution = createExecution(executionId, "test-topic", WorkflowStatus.PENDING);
            repository.save(execution);

            // When
            var toUpdate = repository.findById(executionId).orElseThrow();
            toUpdate.setStatus(WorkflowStatus.COMPLETED);
            toUpdate.setCompletedAt(Instant.now());
            repository.save(toUpdate);

            // Then
            var updated = repository.findById(executionId).orElseThrow();

            assertThat(updated)
                    .extracting(WorkflowExecution::getStatus, WorkflowExecution::getCompletedAt)
                    .satisfies(values -> {
                        assertThat(values.get(0)).isEqualTo(WorkflowStatus.COMPLETED);
                        assertThat(values.get(1)).isNotNull();
                    });
        }
    }

    @Nested
    @DisplayName("Query by status")
    class QueryByStatusTests {

        @Test
        @DisplayName("Should find executions by status")
        void shouldFindExecutionsByStatus() {
            // Given
            var pending1 = createExecution("exec-1", "topic-a", WorkflowStatus.PENDING);
            var pending2 = createExecution("exec-2", "topic-b", WorkflowStatus.PENDING);
            var completed = createExecution("exec-3", "topic-a", WorkflowStatus.COMPLETED);
            var failed = createExecution("exec-4", "topic-c", WorkflowStatus.FAILED);

            repository.saveAll(List.of(pending1, pending2, completed, failed));

            // When
            var pageable = PageRequest.of(0, 10);
            var pendingExecutions = repository.findByStatus(WorkflowStatus.PENDING, pageable);

            // Then
            assertThat(pendingExecutions.getContent())
                    .hasSize(2)
                    .extracting(WorkflowExecution::getExecutionId, WorkflowExecution::getStatus)
                    .containsExactlyInAnyOrder(
                            tuple("exec-1", WorkflowStatus.PENDING),
                            tuple("exec-2", WorkflowStatus.PENDING)
                    );
        }

        @Test
        @DisplayName("Should count executions by status")
        void shouldCountExecutionsByStatus() {
            // Given
            var executions = List.of(
                    createExecution("e1", "t1", WorkflowStatus.PENDING),
                    createExecution("e2", "t2", WorkflowStatus.PENDING),
                    createExecution("e3", "t3", WorkflowStatus.IN_PROGRESS),
                    createExecution("e4", "t4", WorkflowStatus.COMPLETED),
                    createExecution("e5", "t5", WorkflowStatus.COMPLETED),
                    createExecution("e6", "t6", WorkflowStatus.COMPLETED),
                    createExecution("e7", "t7", WorkflowStatus.FAILED)
            );
            repository.saveAll(executions);

            // When
            var pendingCount = repository.countByStatus(WorkflowStatus.PENDING);
            var completedCount = repository.countByStatus(WorkflowStatus.COMPLETED);
            var failedCount = repository.countByStatus(WorkflowStatus.FAILED);

            // Then
            assertThat(pendingCount).isEqualTo(2);
            assertThat(completedCount).isEqualTo(3);
            assertThat(failedCount).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Query by topic")
    class QueryByTopicTests {

        @Test
        @DisplayName("Should find executions by topic")
        void shouldFindExecutionsByTopic() {
            // Given
            var orderExec1 = createExecution("e1", "order-workflow", WorkflowStatus.PENDING);
            var orderExec2 = createExecution("e2", "order-workflow", WorkflowStatus.COMPLETED);
            var paymentExec = createExecution("e3", "payment-workflow", WorkflowStatus.PENDING);

            repository.saveAll(List.of(orderExec1, orderExec2, paymentExec));

            // When
            var pageable = PageRequest.of(0, 10);
            var orderExecutions = repository.findByTopic("order-workflow", pageable);

            // Then
            assertThat(orderExecutions.getContent())
                    .hasSize(2)
                    .extracting(WorkflowExecution::getTopic)
                    .containsOnly("order-workflow");
        }

        @Test
        @DisplayName("Should find executions by topic and status")
        void shouldFindExecutionsByTopicAndStatus() {
            // Given
            var executions = List.of(
                    createExecution("e1", "order-workflow", WorkflowStatus.PENDING),
                    createExecution("e2", "order-workflow", WorkflowStatus.COMPLETED),
                    createExecution("e3", "order-workflow", WorkflowStatus.FAILED),
                    createExecution("e4", "payment-workflow", WorkflowStatus.PENDING)
            );
            repository.saveAll(executions);

            // When
            var pageable = PageRequest.of(0, 10);
            var result = repository.findByTopicAndStatus("order-workflow", WorkflowStatus.PENDING, pageable);

            // Then
            assertThat(result.getContent())
                    .hasSize(1)
                    .extracting(WorkflowExecution::getExecutionId, WorkflowExecution::getTopic, WorkflowExecution::getStatus)
                    .containsExactly(tuple("e1", "order-workflow", WorkflowStatus.PENDING));
        }
    }

    @Nested
    @DisplayName("Pagination and sorting")
    class PaginationAndSortingTests {

        @Test
        @DisplayName("Should return paginated results")
        void shouldReturnPaginatedResults() {
            // Given
            var executions = IntStream.rangeClosed(1, 25)
                    .mapToObj(i -> createExecution("exec-" + i, "topic", WorkflowStatus.PENDING))
                    .toList();
            repository.saveAll(executions);

            // When
            var page1 = repository.findAll(PageRequest.of(0, 10));
            var page2 = repository.findAll(PageRequest.of(1, 10));
            var page3 = repository.findAll(PageRequest.of(2, 10));

            // Then
            assertThat(page1.getContent()).hasSize(10);
            assertThat(page2.getContent()).hasSize(10);
            assertThat(page3.getContent()).hasSize(5);
            assertThat(page1.getTotalElements()).isEqualTo(25);
            assertThat(page1.getTotalPages()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should return sorted results by createdAt descending")
        void shouldReturnSortedResultsByCreatedAtDescending() {
            // Given
            var now = Instant.now();
            var exec1 = createExecutionWithTimestamp("e1", "topic", now.minus(3, ChronoUnit.HOURS));
            var exec2 = createExecutionWithTimestamp("e2", "topic", now.minus(1, ChronoUnit.HOURS));
            var exec3 = createExecutionWithTimestamp("e3", "topic", now.minus(2, ChronoUnit.HOURS));

            repository.saveAll(List.of(exec1, exec2, exec3));

            // When
            var pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
            var sorted = repository.findAll(pageable);

            // Then
            assertThat(sorted.getContent())
                    .extracting(WorkflowExecution::getExecutionId)
                    .containsExactly("e2", "e3", "e1");
        }
    }

    @Nested
    @DisplayName("Recent executions")
    class RecentExecutionsTests {

        @Test
        @DisplayName("Should find top 10 recent executions")
        void shouldFindTop10RecentExecutions() {
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

            // When
            var recent = repository.findTop10ByOrderByCreatedAtDesc();

            // Then
            assertThat(recent)
                    .hasSize(10)
                    .extracting(WorkflowExecution::getExecutionId)
                    .containsExactly(
                            "exec-1", "exec-2", "exec-3", "exec-4", "exec-5",
                            "exec-6", "exec-7", "exec-8", "exec-9", "exec-10"
                    );
        }
    }

    @Nested
    @DisplayName("Retry management")
    class RetryManagementTests {

        @Test
        @DisplayName("Should find pending retries ready for execution")
        void shouldFindPendingRetriesReadyForExecution() {
            // Given
            var now = Instant.now();
            var readyRetry1 = createRetryExecution("r1", now.minus(5, ChronoUnit.MINUTES));
            var readyRetry2 = createRetryExecution("r2", now.minus(1, ChronoUnit.MINUTES));
            var futureRetry = createRetryExecution("r3", now.plus(10, ChronoUnit.MINUTES));
            var completed = createExecution("c1", "topic", WorkflowStatus.COMPLETED);

            repository.saveAll(List.of(readyRetry1, readyRetry2, futureRetry, completed));

            // When
            var pendingRetries = repository.findPendingRetries(now);

            // Then
            assertThat(pendingRetries)
                    .hasSize(2)
                    .extracting(WorkflowExecution::getExecutionId)
                    .containsExactlyInAnyOrder("r1", "r2");
        }
    }

    @Nested
    @DisplayName("Retention purge operations")
    class RetentionPurgeTests {

        @Test
        @DisplayName("Should delete COMPLETED executions older than the cutoff, by updatedAt")
        void shouldDeleteCompletedExecutionsOlderThanCutoff() {
            // Given
            var now = Instant.now();
            var cutoff = now.minus(7, ChronoUnit.DAYS);

            var oldCompleted = createExecutionWithAge("old1", WorkflowStatus.COMPLETED, now.minus(10, ChronoUnit.DAYS));
            var recentCompleted = createExecutionWithAge("recent1", WorkflowStatus.COMPLETED, now.minus(1, ChronoUnit.DAYS));
            var oldFailed = createExecutionWithAge("old-failed", WorkflowStatus.FAILED, now.minus(10, ChronoUnit.DAYS));

            repository.saveAll(List.of(oldCompleted, recentCompleted, oldFailed));

            // When
            long deleted = repository.deleteByStatusAndAgeBefore(WorkflowStatus.COMPLETED, cutoff);

            // Then
            assertThat(deleted).isEqualTo(1);
            assertThat(repository.findAll())
                    .extracting(WorkflowExecution::getExecutionId)
                    .containsExactlyInAnyOrder("recent1", "old-failed");
        }

        @Test
        @DisplayName("Should fall back to createdAt when updatedAt is null")
        void shouldFallBackToCreatedAtWhenUpdatedAtIsNull() {
            // Given
            var now = Instant.now();
            var cutoff = now.minus(7, ChronoUnit.DAYS);

            var oldNoUpdatedAt = WorkflowExecution.builder()
                    .executionId("old-no-updated-at")
                    .topic("topic")
                    .status(WorkflowStatus.FAILED)
                    .currentStep(1)
                    .totalSteps(1)
                    .createdAt(now.minus(10, ChronoUnit.DAYS))
                    .build();
            var recentNoUpdatedAt = WorkflowExecution.builder()
                    .executionId("recent-no-updated-at")
                    .topic("topic")
                    .status(WorkflowStatus.FAILED)
                    .currentStep(1)
                    .totalSteps(1)
                    .createdAt(now.minus(1, ChronoUnit.DAYS))
                    .build();

            repository.saveAll(List.of(oldNoUpdatedAt, recentNoUpdatedAt));

            // When
            long deleted = repository.deleteByStatusAndAgeBefore(WorkflowStatus.FAILED, cutoff);

            // Then
            assertThat(deleted).isEqualTo(1);
            assertThat(repository.findAll())
                    .extracting(WorkflowExecution::getExecutionId)
                    .containsExactly("recent-no-updated-at");
        }

        @Test
        @DisplayName("Should delete stuck (IN_PROGRESS/PENDING/RETRY_PENDING) and CANCELLED executions "
                + "regardless of status (F2/F11), while sparing COMPLETED/FAILED handled by their own TTL")
        void shouldDeleteStuckAndCancelledExecutionsRegardlessOfStatus() {
            // Given
            var now = Instant.now();
            var cutoff = now.minus(30, ChronoUnit.DAYS);

            var oldInProgress = createExecutionWithAge("old-in-progress", WorkflowStatus.IN_PROGRESS, now.minus(40, ChronoUnit.DAYS));
            var oldPending = createExecutionWithAge("old-pending", WorkflowStatus.PENDING, now.minus(40, ChronoUnit.DAYS));
            var oldRetryPending = createExecutionWithAge("old-retry-pending", WorkflowStatus.RETRY_PENDING, now.minus(40, ChronoUnit.DAYS));
            var oldCancelled = createExecutionWithAge("old-cancelled", WorkflowStatus.CANCELLED, now.minus(40, ChronoUnit.DAYS));
            var recentInProgress = createExecutionWithAge("recent-in-progress", WorkflowStatus.IN_PROGRESS, now.minus(1, ChronoUnit.DAYS));
            var oldCompleted = createExecutionWithAge("old-completed", WorkflowStatus.COMPLETED, now.minus(40, ChronoUnit.DAYS));
            var oldFailed = createExecutionWithAge("old-failed", WorkflowStatus.FAILED, now.minus(40, ChronoUnit.DAYS));

            repository.saveAll(List.of(oldInProgress, oldPending, oldRetryPending, oldCancelled,
                    recentInProgress, oldCompleted, oldFailed));

            // When
            long deleted = repository.deleteByStatusNotInAndAgeBefore(
                    List.of(WorkflowStatus.COMPLETED, WorkflowStatus.FAILED), cutoff);

            // Then
            assertThat(deleted).isEqualTo(4);
            assertThat(repository.findAll())
                    .extracting(WorkflowExecution::getExecutionId)
                    .containsExactlyInAnyOrder("recent-in-progress", "old-completed", "old-failed");
        }
    }

    @Nested
    @DisplayName("Complex queries with step history")
    class StepHistoryTests {

        @Test
        @DisplayName("Should save and retrieve execution with step history")
        void shouldSaveAndRetrieveExecutionWithStepHistory() {
            // Given
            var stepHistory = List.of(
                    WorkflowExecution.StepExecution.builder()
                            .stepId(1)
                            .stepLabel("Validate")
                            .status(WorkflowStatus.COMPLETED)
                            .startedAt(Instant.now().minus(10, ChronoUnit.MINUTES))
                            .completedAt(Instant.now().minus(9, ChronoUnit.MINUTES))
                            .durationMs(60000L)
                            .build(),
                    WorkflowExecution.StepExecution.builder()
                            .stepId(2)
                            .stepLabel("Process")
                            .status(WorkflowStatus.IN_PROGRESS)
                            .startedAt(Instant.now().minus(8, ChronoUnit.MINUTES))
                            .attempt(1)
                            .build()
            );

            var execution = WorkflowExecution.builder()
                    .executionId(UUID.randomUUID().toString())
                    .topic("multi-step-workflow")
                    .status(WorkflowStatus.IN_PROGRESS)
                    .currentStep(2)
                    .totalSteps(3)
                    .stepHistory(stepHistory)
                    .createdAt(Instant.now())
                    .build();

            // When
            repository.save(execution);
            var found = repository.findById(execution.getExecutionId()).orElseThrow();

            // Then
            assertThat(found.getStepHistory())
                    .hasSize(2)
                    .extracting(
                            WorkflowExecution.StepExecution::getStepId,
                            WorkflowExecution.StepExecution::getStepLabel,
                            WorkflowExecution.StepExecution::getStatus
                    )
                    .containsExactly(
                            tuple(1, "Validate", WorkflowStatus.COMPLETED),
                            tuple(2, "Process", WorkflowStatus.IN_PROGRESS)
                    );
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

    private WorkflowExecution createRetryExecution(String executionId, Instant nextRetryAt) {
        var retryInfo = RetryInfo.builder()
                .attempt(1)
                .maxAttempts(3)
                .nextRetryAt(nextRetryAt)
                .build();

        return WorkflowExecution.builder()
                .executionId(executionId)
                .topic("retry-topic")
                .status(WorkflowStatus.RETRY_PENDING)
                .currentStep(1)
                .totalSteps(1)
                .retryInfo(retryInfo)
                .createdAt(Instant.now())
                .build();
    }

    /**
     * Builds an execution whose age is driven by {@code updatedAt} (the field the
     * retention purge queries key off), as opposed to {@code createdAt} or
     * {@code completedAt}.
     */
    private WorkflowExecution createExecutionWithAge(String executionId, WorkflowStatus status, Instant updatedAt) {
        return WorkflowExecution.builder()
                .executionId(executionId)
                .topic("topic")
                .status(status)
                .currentStep(1)
                .totalSteps(1)
                .createdAt(updatedAt.minus(1, ChronoUnit.HOURS))
                .updatedAt(updatedAt)
                .build();
    }
}
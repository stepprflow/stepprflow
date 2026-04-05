package io.github.stepprflow.monitor.service;

import io.github.stepprflow.core.event.WorkflowMessageEvent;
import io.github.stepprflow.core.model.ErrorInfo;
import io.github.stepprflow.core.model.RetryInfo;
import io.github.stepprflow.core.model.StepDefinition;
import io.github.stepprflow.core.model.WorkflowDefinition;
import io.github.stepprflow.core.model.WorkflowMessage;
import io.github.stepprflow.core.model.WorkflowStatus;
import io.github.stepprflow.core.service.WorkflowRegistry;
import io.github.stepprflow.monitor.model.WorkflowExecution;
import io.github.stepprflow.monitor.repository.RegisteredWorkflowRepository;
import io.github.stepprflow.monitor.repository.WorkflowExecutionRepository;
import io.github.stepprflow.monitor.websocket.WorkflowWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionPersistenceService Tests")
class ExecutionPersistenceServiceTest {

    @Mock
    private WorkflowExecutionRepository repository;

    @Mock
    private WorkflowWebSocketHandler webSocketHandler;

    @Mock
    private RegisteredWorkflowRepository registeredWorkflowRepository;

    @InjectMocks
    private ExecutionPersistenceService persistenceService;

    @Captor
    private ArgumentCaptor<WorkflowExecution> executionCaptor;

    private WorkflowMessage testMessage;

    @BeforeEach
    void setUp() {
        testMessage = WorkflowMessage.builder()
                .executionId("exec-123")
                .correlationId("corr-456")
                .topic("test-topic")
                .currentStep(1)
                .totalSteps(3)
                .status(WorkflowStatus.IN_PROGRESS)
                .payload(Map.of("key", "value"))
                .payloadType("java.util.Map")
                .securityContext("token-abc")
                .metadata(Map.of("user", "test-user"))
                .createdAt(Instant.now())
                .build();
    }

    @Nested
    @DisplayName("onWorkflowMessage() method")
    class OnWorkflowMessageTests {

        @Test
        @DisplayName("Should ignore null message")
        void shouldIgnoreNullMessage() {
            persistenceService.onWorkflowMessage(null);

            verify(repository, never()).save(any());
            verify(webSocketHandler, never()).broadcastUpdate(any());
        }

        @Test
        @DisplayName("Should create new execution for new message")
        void shouldCreateNewExecutionForNewMessage() {
            when(repository.findById("exec-123")).thenReturn(Optional.empty());

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            assertThat(saved.getExecutionId()).isEqualTo("exec-123");
            assertThat(saved.getCorrelationId()).isEqualTo("corr-456");
            assertThat(saved.getTopic()).isEqualTo("test-topic");
            assertThat(saved.getTotalSteps()).isEqualTo(3);
            assertThat(saved.getPayload()).isEqualTo(testMessage.getPayload());
            assertThat(saved.getPayloadType()).isEqualTo("java.util.Map");
            assertThat(saved.getSecurityContext()).isEqualTo("token-abc");
            assertThat(saved.getMetadata()).isEqualTo(testMessage.getMetadata());
        }

        @Test
        @DisplayName("Should update existing execution")
        void shouldUpdateExistingExecution() {
            WorkflowExecution existing = WorkflowExecution.builder()
                    .executionId("exec-123")
                    .topic("test-topic")
                    .status(WorkflowStatus.PENDING)
                    .currentStep(1)
                    .stepHistory(new ArrayList<>())
                    .build();
            when(repository.findById("exec-123")).thenReturn(Optional.of(existing));

            testMessage = testMessage.toBuilder()
                    .currentStep(2)
                    .status(WorkflowStatus.IN_PROGRESS)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            assertThat(saved.getStatus()).isEqualTo(WorkflowStatus.IN_PROGRESS);
            assertThat(saved.getCurrentStep()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should broadcast update via WebSocket")
        void shouldBroadcastUpdateViaWebSocket() {
            when(repository.findById("exec-123")).thenReturn(Optional.empty());

            persistenceService.onWorkflowMessage(testMessage);

            verify(webSocketHandler).broadcastUpdate(any(WorkflowExecution.class));
        }

        @Test
        @DisplayName("Should set updatedAt timestamp")
        void shouldSetUpdatedAtTimestamp() {
            when(repository.findById("exec-123")).thenReturn(Optional.empty());

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            assertThat(saved.getUpdatedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Status handling")
    class StatusHandlingTests {

        @Test
        @DisplayName("Should update retry info")
        void shouldUpdateRetryInfo() {
            when(repository.findById("exec-123")).thenReturn(Optional.empty());

            RetryInfo retryInfo = RetryInfo.builder()
                    .attempt(2)
                    .maxAttempts(3)
                    .nextRetryAt(Instant.now().plusSeconds(60))
                    .lastError("Previous error")
                    .build();
            testMessage = testMessage.toBuilder()
                    .retryInfo(retryInfo)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            assertThat(saved.getRetryInfo()).isEqualTo(retryInfo);
        }

        @Test
        @DisplayName("Should update error info")
        void shouldUpdateErrorInfo() {
            when(repository.findById("exec-123")).thenReturn(Optional.empty());

            ErrorInfo errorInfo = ErrorInfo.builder()
                    .code("ERR_001")
                    .message("Something went wrong")
                    .exceptionType("java.lang.RuntimeException")
                    .build();
            testMessage = testMessage.toBuilder()
                    .errorInfo(errorInfo)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            assertThat(saved.getErrorInfo()).isEqualTo(errorInfo);
        }
    }

    @Nested
    @DisplayName("Completion handling")
    class CompletionHandlingTests {

        @Test
        @DisplayName("Should set completedAt for COMPLETED status")
        void shouldSetCompletedAtForCompletedStatus() {
            when(repository.findById("exec-123")).thenReturn(Optional.empty());

            testMessage = testMessage.toBuilder()
                    .status(WorkflowStatus.COMPLETED)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            assertThat(saved.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("Should set completedAt for FAILED status")
        void shouldSetCompletedAtForFailedStatus() {
            when(repository.findById("exec-123")).thenReturn(Optional.empty());

            testMessage = testMessage.toBuilder()
                    .status(WorkflowStatus.FAILED)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            assertThat(saved.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("Should calculate duration on completion")
        void shouldCalculateDurationOnCompletion() {
            Instant createdAt = Instant.now().minusSeconds(10);
            when(repository.findById("exec-123")).thenReturn(Optional.empty());

            testMessage = testMessage.toBuilder()
                    .status(WorkflowStatus.COMPLETED)
                    .createdAt(createdAt)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            assertThat(saved.getDurationMs()).isNotNull();
            assertThat(saved.getDurationMs()).isGreaterThan(0);
            // Duration should be approximately 10 seconds (10000ms), not negative
            assertThat(saved.getDurationMs()).isBetween(9000L, 15000L);
        }

        @Test
        @DisplayName("Should set completedAt for CANCELLED status")
        void shouldSetCompletedAtForCancelledStatus() {
            when(repository.findById("exec-123")).thenReturn(Optional.empty());

            testMessage = testMessage.toBuilder()
                    .status(WorkflowStatus.CANCELLED)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            assertThat(saved.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("Should finalize execution attempt on completion")
        void shouldFinalizeExecutionAttemptOnCompletion() {
            // Create execution with an active attempt
            WorkflowExecution.ExecutionAttempt activeAttempt = WorkflowExecution.ExecutionAttempt.builder()
                    .attemptNumber(1)
                    .startedAt(Instant.now().minusSeconds(10))
                    .startStep(1)
                    .build();

            ArrayList<WorkflowExecution.ExecutionAttempt> attempts = new ArrayList<>();
            attempts.add(activeAttempt);

            WorkflowExecution existing = WorkflowExecution.builder()
                    .executionId("exec-123")
                    .topic("test-topic")
                    .status(WorkflowStatus.IN_PROGRESS)
                    .currentStep(3)
                    .stepHistory(new ArrayList<>())
                    .executionAttempts(attempts)
                    .build();
            when(repository.findById("exec-123")).thenReturn(Optional.of(existing));

            testMessage = testMessage.toBuilder()
                    .status(WorkflowStatus.COMPLETED)
                    .currentStep(3)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            // Attempt should be finalized
            WorkflowExecution.ExecutionAttempt finalizedAttempt = saved.getExecutionAttempts().get(0);
            assertThat(finalizedAttempt.getEndedAt()).isNotNull();
            assertThat(finalizedAttempt.getResult()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(finalizedAttempt.getEndStep()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should set error message in attempt on failure")
        void shouldSetErrorMessageInAttemptOnFailure() {
            // Create execution with an active attempt
            WorkflowExecution.ExecutionAttempt activeAttempt = WorkflowExecution.ExecutionAttempt.builder()
                    .attemptNumber(1)
                    .startedAt(Instant.now().minusSeconds(10))
                    .startStep(1)
                    .build();

            ArrayList<WorkflowExecution.ExecutionAttempt> attempts = new ArrayList<>();
            attempts.add(activeAttempt);

            WorkflowExecution existing = WorkflowExecution.builder()
                    .executionId("exec-123")
                    .topic("test-topic")
                    .status(WorkflowStatus.IN_PROGRESS)
                    .currentStep(2)
                    .stepHistory(new ArrayList<>())
                    .executionAttempts(attempts)
                    .build();
            when(repository.findById("exec-123")).thenReturn(Optional.of(existing));

            ErrorInfo errorInfo = ErrorInfo.builder()
                    .message("Workflow failed at step 2")
                    .build();
            testMessage = testMessage.toBuilder()
                    .status(WorkflowStatus.FAILED)
                    .currentStep(2)
                    .errorInfo(errorInfo)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            WorkflowExecution.ExecutionAttempt finalizedAttempt = saved.getExecutionAttempts().get(0);
            assertThat(finalizedAttempt.getErrorMessage()).isEqualTo("Workflow failed at step 2");
        }
    }

    @Nested
    @DisplayName("Step history tracking")
    class StepHistoryTrackingTests {

        @Test
        @DisplayName("Should add step to history for PENDING status")
        void shouldAddStepToHistoryForPendingStatus() {
            when(repository.findById("exec-123")).thenReturn(Optional.empty());

            testMessage = testMessage.toBuilder()
                    .status(WorkflowStatus.PENDING)
                    .currentStep(1)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            assertThat(saved.getStepHistory()).isNotNull();
            assertThat(saved.getStepHistory()).hasSize(1);
        }

        @Test
        @DisplayName("Should add step to history for RETRY_PENDING status")
        void shouldAddStepToHistoryForRetryPendingStatus() {
            when(repository.findById("exec-123")).thenReturn(Optional.empty());

            testMessage = testMessage.toBuilder()
                    .status(WorkflowStatus.RETRY_PENDING)
                    .currentStep(1)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            assertThat(saved.getStepHistory()).isNotNull();
            assertThat(saved.getStepHistory()).hasSize(1);
        }

        @Test
        @DisplayName("Should add step to history for IN_PROGRESS status")
        void shouldAddStepToHistoryForInProgressStatus() {
            when(repository.findById("exec-123")).thenReturn(Optional.empty());

            testMessage = testMessage.toBuilder()
                    .status(WorkflowStatus.IN_PROGRESS)
                    .currentStep(1)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            assertThat(saved.getStepHistory()).isNotNull();
            assertThat(saved.getStepHistory()).hasSize(1);
            assertThat(saved.getStepHistory().get(0).getStepId()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should mark previous steps as PASSED when advancing to next step")
        void shouldMarkPreviousStepsAsPassedWhenAdvancing() {
            // Create existing execution with step 1 IN_PROGRESS
            WorkflowExecution.StepExecution step1 = WorkflowExecution.StepExecution.builder()
                    .stepId(1)
                    .startedAt(Instant.now().minusSeconds(10))
                    .status(WorkflowStatus.IN_PROGRESS)
                    .build();

            ArrayList<WorkflowExecution.StepExecution> history = new ArrayList<>();
            history.add(step1);

            WorkflowExecution existing = WorkflowExecution.builder()
                    .executionId("exec-123")
                    .topic("test-topic")
                    .currentStep(1)
                    .status(WorkflowStatus.IN_PROGRESS)
                    .stepHistory(history)
                    .build();
            when(repository.findById("exec-123")).thenReturn(Optional.of(existing));

            // Now advance to step 2
            testMessage = testMessage.toBuilder()
                    .status(WorkflowStatus.IN_PROGRESS)
                    .currentStep(2)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            // Step 1 should be marked as PASSED
            WorkflowExecution.StepExecution savedStep1 = saved.getStepHistory().stream()
                    .filter(s -> s.getStepId() == 1)
                    .findFirst()
                    .orElseThrow();
            assertThat(savedStep1.getStatus()).isEqualTo(WorkflowStatus.PASSED);
            assertThat(savedStep1.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("Should add step to history for COMPLETED status")
        void shouldAddStepToHistoryForCompletedStatus() {
            when(repository.findById("exec-123")).thenReturn(Optional.empty());

            testMessage = testMessage.toBuilder()
                    .status(WorkflowStatus.COMPLETED)
                    .currentStep(3)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            assertThat(saved.getStepHistory()).isNotNull();
            assertThat(saved.getStepHistory()).isNotEmpty();
        }

        @Test
        @DisplayName("Should add step to history for FAILED status")
        void shouldAddStepToHistoryForFailedStatus() {
            when(repository.findById("exec-123")).thenReturn(Optional.empty());

            testMessage = testMessage.toBuilder()
                    .status(WorkflowStatus.FAILED)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            assertThat(saved.getStepHistory()).isNotNull();
        }

        @Test
        @DisplayName("Should update existing step in history")
        void shouldUpdateExistingStepInHistory() {
            WorkflowExecution.StepExecution existingStep = WorkflowExecution.StepExecution.builder()
                    .stepId(1)
                    .startedAt(Instant.now().minusSeconds(5))
                    .status(WorkflowStatus.IN_PROGRESS)
                    .build();

            ArrayList<WorkflowExecution.StepExecution> history = new ArrayList<>();
            history.add(existingStep);

            WorkflowExecution existing = WorkflowExecution.builder()
                    .executionId("exec-123")
                    .topic("test-topic")
                    .stepHistory(history)
                    .build();
            when(repository.findById("exec-123")).thenReturn(Optional.of(existing));

            testMessage = testMessage.toBuilder()
                    .status(WorkflowStatus.COMPLETED)
                    .currentStep(1)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            assertThat(saved.getStepHistory()).hasSize(1);
            assertThat(saved.getStepHistory().get(0).getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(saved.getStepHistory().get(0).getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("Should record error message in step history")
        void shouldRecordErrorMessageInStepHistory() {
            when(repository.findById("exec-123")).thenReturn(Optional.empty());

            ErrorInfo errorInfo = ErrorInfo.builder()
                    .message("Step failed with error")
                    .build();
            testMessage = testMessage.toBuilder()
                    .status(WorkflowStatus.FAILED)
                    .errorInfo(errorInfo)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            assertThat(saved.getStepHistory().get(0).getErrorMessage()).isEqualTo("Step failed with error");
        }

        @Test
        @DisplayName("Should record retry attempt in step history")
        void shouldRecordRetryAttemptInStepHistory() {
            when(repository.findById("exec-123")).thenReturn(Optional.empty());

            RetryInfo retryInfo = RetryInfo.builder()
                    .attempt(3)
                    .maxAttempts(5)
                    .build();
            testMessage = testMessage.toBuilder()
                    .status(WorkflowStatus.IN_PROGRESS)
                    .retryInfo(retryInfo)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            assertThat(saved.getStepHistory().get(0).getAttempt()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Step duration calculation")
    class StepDurationCalculationTests {

        @Test
        @DisplayName("Should calculate step duration correctly when startedAt is set")
        void shouldCalculateStepDurationCorrectlyWhenStartedAtIsSet() {
            // Create existing execution with a step that has startedAt
            Instant stepStartTime = Instant.now().minusSeconds(5);
            WorkflowExecution.StepExecution existingStep = WorkflowExecution.StepExecution.builder()
                    .stepId(1)
                    .startedAt(stepStartTime)
                    .status(WorkflowStatus.IN_PROGRESS)
                    .build();

            ArrayList<WorkflowExecution.StepExecution> history = new ArrayList<>();
            history.add(existingStep);

            WorkflowExecution existing = WorkflowExecution.builder()
                    .executionId("exec-123")
                    .topic("test-topic")
                    .stepHistory(history)
                    .build();
            when(repository.findById("exec-123")).thenReturn(Optional.of(existing));

            testMessage = testMessage.toBuilder()
                    .status(WorkflowStatus.COMPLETED)
                    .currentStep(1)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            // Step duration should be positive (approximately 5 seconds)
            Long durationMs = saved.getStepHistory().get(0).getDurationMs();
            assertThat(durationMs).isNotNull();
            assertThat(durationMs).isGreaterThanOrEqualTo(5000L);
            assertThat(durationMs).isLessThan(10000L);
        }

        @Test
        @DisplayName("Should not calculate step duration when startedAt is null")
        void shouldNotCalculateStepDurationWhenStartedAtIsNull() {
            // Create existing execution with a step that has no startedAt
            WorkflowExecution.StepExecution existingStep = WorkflowExecution.StepExecution.builder()
                    .stepId(1)
                    .status(WorkflowStatus.IN_PROGRESS)
                    .build();

            ArrayList<WorkflowExecution.StepExecution> history = new ArrayList<>();
            history.add(existingStep);

            WorkflowExecution existing = WorkflowExecution.builder()
                    .executionId("exec-123")
                    .topic("test-topic")
                    .stepHistory(history)
                    .build();
            when(repository.findById("exec-123")).thenReturn(Optional.of(existing));

            testMessage = testMessage.toBuilder()
                    .status(WorkflowStatus.COMPLETED)
                    .currentStep(1)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            assertThat(saved.getStepHistory().get(0).getDurationMs()).isNull();
        }
    }

    @Nested
    @DisplayName("Step status handling")
    class StepStatusHandlingTests {

        @Test
        @DisplayName("Should set step status to IN_PROGRESS for IN_PROGRESS message")
        void shouldSetStepStatusToInProgressForInProgressMessage() {
            WorkflowExecution.StepExecution existingStep = WorkflowExecution.StepExecution.builder()
                    .stepId(1)
                    .startedAt(Instant.now().minusSeconds(5))
                    .status(WorkflowStatus.PENDING)
                    .build();

            ArrayList<WorkflowExecution.StepExecution> history = new ArrayList<>();
            history.add(existingStep);

            WorkflowExecution existing = WorkflowExecution.builder()
                    .executionId("exec-123")
                    .topic("test-topic")
                    .stepHistory(history)
                    .build();
            when(repository.findById("exec-123")).thenReturn(Optional.of(existing));

            testMessage = testMessage.toBuilder()
                    .status(WorkflowStatus.IN_PROGRESS)
                    .currentStep(1)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            assertThat(saved.getStepHistory().get(0).getStatus()).isEqualTo(WorkflowStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("Should set step status to PENDING for PENDING message")
        void shouldSetStepStatusToPendingForPendingMessage() {
            when(repository.findById("exec-123")).thenReturn(Optional.empty());

            testMessage = testMessage.toBuilder()
                    .status(WorkflowStatus.PENDING)
                    .currentStep(1)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            // Step should be created with the same status
            assertThat(saved.getStepHistory().get(0).getStatus()).isEqualTo(WorkflowStatus.PENDING);
        }

        @Test
        @DisplayName("Should set step status to FAILED for FAILED message")
        void shouldSetStepStatusToFailedForFailedMessage() {
            WorkflowExecution.StepExecution existingStep = WorkflowExecution.StepExecution.builder()
                    .stepId(1)
                    .startedAt(Instant.now().minusSeconds(5))
                    .status(WorkflowStatus.IN_PROGRESS)
                    .build();

            ArrayList<WorkflowExecution.StepExecution> history = new ArrayList<>();
            history.add(existingStep);

            WorkflowExecution existing = WorkflowExecution.builder()
                    .executionId("exec-123")
                    .topic("test-topic")
                    .stepHistory(history)
                    .build();
            when(repository.findById("exec-123")).thenReturn(Optional.of(existing));

            testMessage = testMessage.toBuilder()
                    .status(WorkflowStatus.FAILED)
                    .currentStep(1)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            assertThat(saved.getStepHistory().get(0).getStatus()).isEqualTo(WorkflowStatus.FAILED);
            assertThat(saved.getStepHistory().get(0).getCompletedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("CreatedAt handling")
    class CreatedAtHandlingTests {

        @Test
        @DisplayName("Should use message createdAt if present")
        void shouldUseMessageCreatedAtIfPresent() {
            Instant messageCreatedAt = Instant.parse("2024-01-15T10:00:00Z");
            when(repository.findById("exec-123")).thenReturn(Optional.empty());

            testMessage = testMessage.toBuilder()
                    .createdAt(messageCreatedAt)
                    .build();

            persistenceService.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            assertThat(saved.getCreatedAt()).isEqualTo(messageCreatedAt);
        }

        @Test
        @DisplayName("Should use current time if message createdAt is null")
        void shouldUseCurrentTimeIfMessageCreatedAtIsNull() {
            when(repository.findById("exec-123")).thenReturn(Optional.empty());

            testMessage = testMessage.toBuilder()
                    .createdAt(null)
                    .build();

            Instant before = Instant.now();
            persistenceService.onWorkflowMessage(testMessage);
            Instant after = Instant.now();

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();

            assertThat(saved.getCreatedAt()).isBetween(before, after.plusMillis(1));
        }
    }

    @Nested
    @DisplayName("handleWorkflowMessageEvent() method")
    class HandleWorkflowMessageEventTests {

        @Test
        @DisplayName("Should delegate to onWorkflowMessage")
        void shouldDelegateToOnWorkflowMessage() {
            when(repository.findById("exec-123")).thenReturn(Optional.empty());

            WorkflowMessageEvent event = new WorkflowMessageEvent(persistenceService, testMessage);
            persistenceService.handleWorkflowMessageEvent(event);

            verify(repository).save(any());
        }
    }

    @Nested
    @DisplayName("Step label resolution")
    class StepLabelResolutionTests {

        @Mock
        private WorkflowRegistry workflowRegistry;

        private ExecutionPersistenceService serviceWithRegistry;

        @BeforeEach
        void setUp() {
            serviceWithRegistry = new ExecutionPersistenceService(repository, webSocketHandler, workflowRegistry, registeredWorkflowRepository);
        }

        @Test
        @DisplayName("Should resolve step label from workflow registry")
        void shouldResolveStepLabel() {
            WorkflowDefinition definition = WorkflowDefinition.builder()
                    .topic("test-topic")
                    .steps(List.of(
                            StepDefinition.builder().id(1).label("Validate Order").build()
                    ))
                    .build();
            when(workflowRegistry.getDefinition("test-topic")).thenReturn(definition);
            when(repository.findById("exec-123")).thenReturn(Optional.empty());

            serviceWithRegistry.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();
            assertThat(saved.getStepHistory().get(0).getStepLabel()).isEqualTo("Validate Order");
        }

        @Test
        @DisplayName("Should return null label when definition not found")
        void shouldReturnNullLabelWhenDefinitionNotFound() {
            when(workflowRegistry.getDefinition("test-topic")).thenReturn(null);
            when(repository.findById("exec-123")).thenReturn(Optional.empty());

            serviceWithRegistry.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();
            assertThat(saved.getStepHistory().get(0).getStepLabel()).isNull();
        }

        @Test
        @DisplayName("Should return null label when step not found in definition")
        void shouldReturnNullLabelWhenStepNotFound() {
            WorkflowDefinition definition = WorkflowDefinition.builder()
                    .topic("test-topic")
                    .steps(List.of(
                            StepDefinition.builder().id(99).label("Other Step").build()
                    ))
                    .build();
            when(workflowRegistry.getDefinition("test-topic")).thenReturn(definition);
            when(repository.findById("exec-123")).thenReturn(Optional.empty());

            serviceWithRegistry.onWorkflowMessage(testMessage);

            verify(repository).save(executionCaptor.capture());
            WorkflowExecution saved = executionCaptor.getValue();
            assertThat(saved.getStepHistory().get(0).getStepLabel()).isNull();
        }
    }
}
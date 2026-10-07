package io.github.stepprflow.core.metrics;

import io.github.stepprflow.core.event.WorkflowMessageEvent;
import io.github.stepprflow.core.model.RetryInfo;
import io.github.stepprflow.core.model.WorkflowMessage;
import io.github.stepprflow.core.model.WorkflowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests for WorkflowMetricsListener.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowMetricsListener Tests")
class WorkflowMetricsListenerTest {

    @Mock
    private WorkflowMetrics metrics;

    private WorkflowMetricsListener listener;

    @BeforeEach
    void setUp() {
        listener = new WorkflowMetricsListener(metrics);
    }

    @Nested
    @DisplayName("onWorkflowMessage()")
    class OnWorkflowMessageTests {

        @Test
        @DisplayName("should record workflow started on PENDING status")
        void shouldRecordWorkflowStartedOnPendingStatus() {
            // Given
            WorkflowMessage message = WorkflowMessage.builder()
                    .executionId("exec-123")
                    .topic("order-workflow")
                    .serviceName("order-service")
                    .status(WorkflowStatus.PENDING)
                    .build();
            WorkflowMessageEvent event = new WorkflowMessageEvent(this, message);

            // When
            listener.onWorkflowMessage(event);

            // Then
            verify(metrics).recordWorkflowStarted("order-workflow", "order-service");
        }

        @Test
        @DisplayName("should track step start time on IN_PROGRESS status")
        void shouldTrackStepStartTimeOnInProgressStatus() {
            // Given
            WorkflowMessage message = WorkflowMessage.builder()
                    .executionId("exec-123")
                    .topic("order-workflow")
                    .currentStep(2)
                    .status(WorkflowStatus.IN_PROGRESS)
                    .build();
            WorkflowMessageEvent event = new WorkflowMessageEvent(this, message);

            // When
            listener.onWorkflowMessage(event);

            // Then - no metrics recorded directly, just tracking
            verify(metrics, never()).recordWorkflowStarted(anyString(), anyString());
        }

        @Test
        @DisplayName("should record workflow completed on COMPLETED status")
        void shouldRecordWorkflowCompletedOnCompletedStatus() {
            // Given - first send PENDING to start tracking
            WorkflowMessage pendingMessage = WorkflowMessage.builder()
                    .executionId("exec-123")
                    .topic("order-workflow")
                    .serviceName("order-service")
                    .status(WorkflowStatus.PENDING)
                    .build();
            listener.onWorkflowMessage(new WorkflowMessageEvent(this, pendingMessage));

            // Then send COMPLETED
            WorkflowMessage completedMessage = WorkflowMessage.builder()
                    .executionId("exec-123")
                    .topic("order-workflow")
                    .serviceName("order-service")
                    .status(WorkflowStatus.COMPLETED)
                    .build();
            WorkflowMessageEvent event = new WorkflowMessageEvent(this, completedMessage);

            // When
            listener.onWorkflowMessage(event);

            // Then
            verify(metrics).recordWorkflowCompleted(eq("order-workflow"), eq("order-service"), any(Duration.class));
        }

        @Test
        @DisplayName("should record workflow completed with zero duration when start time not tracked")
        void shouldRecordWorkflowCompletedWithZeroDurationWhenStartTimeNotTracked() {
            // Given - directly send COMPLETED without PENDING
            WorkflowMessage message = WorkflowMessage.builder()
                    .executionId("exec-456")
                    .topic("payment-workflow")
                    .serviceName("payment-service")
                    .status(WorkflowStatus.COMPLETED)
                    .build();
            WorkflowMessageEvent event = new WorkflowMessageEvent(this, message);

            // When
            listener.onWorkflowMessage(event);

            // Then
            verify(metrics).recordWorkflowCompleted(eq("payment-workflow"), eq("payment-service"), eq(Duration.ZERO));
        }

        @Test
        @DisplayName("should record workflow failed and DLQ on FAILED status")
        void shouldRecordWorkflowFailedAndDlqOnFailedStatus() {
            // Given
            WorkflowMessage message = WorkflowMessage.builder()
                    .executionId("exec-789")
                    .topic("inventory-workflow")
                    .serviceName("inventory-service")
                    .status(WorkflowStatus.FAILED)
                    .build();
            WorkflowMessageEvent event = new WorkflowMessageEvent(this, message);

            // When
            listener.onWorkflowMessage(event);

            // Then
            verify(metrics).recordWorkflowFailed(eq("inventory-workflow"), eq("inventory-service"), any(Duration.class));
            verify(metrics).recordDlq("inventory-workflow");
        }

        @Test
        @DisplayName("should record workflow cancelled on CANCELLED status")
        void shouldRecordWorkflowCancelledOnCancelledStatus() {
            // Given
            WorkflowMessage message = WorkflowMessage.builder()
                    .executionId("exec-101")
                    .topic("shipping-workflow")
                    .serviceName("shipping-service")
                    .status(WorkflowStatus.CANCELLED)
                    .build();
            WorkflowMessageEvent event = new WorkflowMessageEvent(this, message);

            // When
            listener.onWorkflowMessage(event);

            // Then
            verify(metrics).recordWorkflowCancelled("shipping-workflow", "shipping-service");
        }

        @Test
        @DisplayName("should record retry on RETRY_PENDING status with retry info")
        void shouldRecordRetryOnRetryPendingStatusWithRetryInfo() {
            // Given
            RetryInfo retryInfo = RetryInfo.builder()
                    .attempt(3)
                    .build();
            WorkflowMessage message = WorkflowMessage.builder()
                    .executionId("exec-202")
                    .topic("notification-workflow")
                    .status(WorkflowStatus.RETRY_PENDING)
                    .retryInfo(retryInfo)
                    .build();
            WorkflowMessageEvent event = new WorkflowMessageEvent(this, message);

            // When
            listener.onWorkflowMessage(event);

            // Then
            verify(metrics).recordRetry("notification-workflow", 3);
        }

        @Test
        @DisplayName("should record retry with attempt 1 when retry info is null")
        void shouldRecordRetryWithAttempt1WhenRetryInfoIsNull() {
            // Given
            WorkflowMessage message = WorkflowMessage.builder()
                    .executionId("exec-303")
                    .topic("email-workflow")
                    .status(WorkflowStatus.RETRY_PENDING)
                    .retryInfo(null)
                    .build();
            WorkflowMessageEvent event = new WorkflowMessageEvent(this, message);

            // When
            listener.onWorkflowMessage(event);

            // Then
            verify(metrics).recordRetry("email-workflow", 1);
        }

        @Test
        @DisplayName("should ignore non-metric statuses like PAUSED")
        void shouldIgnoreNonMetricStatuses() {
            // Given
            WorkflowMessage message = WorkflowMessage.builder()
                    .executionId("exec-404")
                    .topic("paused-workflow")
                    .status(WorkflowStatus.PAUSED)
                    .build();
            WorkflowMessageEvent event = new WorkflowMessageEvent(this, message);

            // When
            listener.onWorkflowMessage(event);

            // Then - no metrics recorded
            verify(metrics, never()).recordWorkflowStarted(anyString(), anyString());
            verify(metrics, never()).recordWorkflowCompleted(anyString(), anyString(), any());
            verify(metrics, never()).recordWorkflowFailed(anyString(), anyString(), any());
            verify(metrics, never()).recordWorkflowCancelled(anyString(), anyString());
            verify(metrics, never()).recordRetry(anyString(), anyInt());
        }

        @Test
        @DisplayName("should ignore SKIPPED status")
        void shouldIgnoreSkippedStatus() {
            // Given
            WorkflowMessage message = WorkflowMessage.builder()
                    .executionId("exec-505")
                    .topic("skipped-workflow")
                    .status(WorkflowStatus.SKIPPED)
                    .build();
            WorkflowMessageEvent event = new WorkflowMessageEvent(this, message);

            // When
            listener.onWorkflowMessage(event);

            // Then - no metrics recorded
            verify(metrics, never()).recordWorkflowStarted(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("recordStepCompleted()")
    class RecordStepCompletedTests {

        @Test
        @DisplayName("should record step executed with duration when step was tracked")
        void shouldRecordStepExecutedWithDurationWhenStepWasTracked() {
            // Given - first start tracking the step
            WorkflowMessage inProgressMessage = WorkflowMessage.builder()
                    .executionId("exec-step-1")
                    .topic("order-workflow")
                    .currentStep(2)
                    .status(WorkflowStatus.IN_PROGRESS)
                    .build();
            listener.onWorkflowMessage(new WorkflowMessageEvent(this, inProgressMessage));

            // When
            listener.recordStepCompleted("order-workflow", "Validate Order", "exec-step-1", 2);

            // Then
            verify(metrics).recordStepExecuted(eq("order-workflow"), eq("Validate Order"), any(Duration.class));
        }

        @Test
        @DisplayName("should record step executed with zero duration when step was not tracked")
        void shouldRecordStepExecutedWithZeroDurationWhenStepWasNotTracked() {
            // When - directly record completion without IN_PROGRESS
            listener.recordStepCompleted("payment-workflow", "Process Payment", "exec-step-2", 3);

            // Then
            verify(metrics).recordStepExecuted("payment-workflow", "Process Payment", Duration.ZERO);
        }
    }

    @Nested
    @DisplayName("recordStepFailed()")
    class RecordStepFailedTests {

        @Test
        @DisplayName("should record step failed and timeout when isTimeout is true")
        void shouldRecordStepFailedAndTimeoutWhenIsTimeoutTrue() {
            // When
            listener.recordStepFailed("order-workflow", "Validate Order", true);

            // Then
            verify(metrics).recordStepTimeout("order-workflow", "Validate Order");
            verify(metrics).recordStepFailed("order-workflow", "Validate Order");
        }

        @Test
        @DisplayName("should record only step failed when isTimeout is false")
        void shouldRecordOnlyStepFailedWhenIsTimeoutFalse() {
            // When
            listener.recordStepFailed("payment-workflow", "Process Payment", false);

            // Then
            verify(metrics, never()).recordStepTimeout(anyString(), anyString());
            verify(metrics).recordStepFailed("payment-workflow", "Process Payment");
        }
    }

    @Nested
    @DisplayName("SF-14: start-time maps are bounded (no unbounded leak)")
    class BoundedTrackingTests {

        @Test
        @DisplayName("BoundedLru evicts the eldest entry once capacity is exceeded")
        void boundedLruEvictsEldest() {
            WorkflowMetricsListener.BoundedLru<String, Integer> map =
                    new WorkflowMetricsListener.BoundedLru<>(2);

            map.put("a", 1);
            map.put("b", 2);
            map.put("c", 3);

            // 'a' (eldest) is evicted; the map never exceeds its capacity.
            assertThat(map).hasSize(2).containsOnlyKeys("b", "c");
        }

        @Test
        @DisplayName("a workflow whose start-time was evicted completes with zero duration "
                + "instead of leaking its entry")
        void evictedWorkflowCompletesWithZeroDuration() {
            // maxTracked=1: each new PENDING evicts the previous execution's start time.
            WorkflowMetricsListener bounded = new WorkflowMetricsListener(metrics, 1);

            bounded.onWorkflowMessage(new WorkflowMessageEvent(this, WorkflowMessage.builder()
                    .executionId("exec-A").topic("t").serviceName("s")
                    .status(WorkflowStatus.PENDING).build()));
            // Second PENDING evicts exec-A's start time (capacity 1).
            bounded.onWorkflowMessage(new WorkflowMessageEvent(this, WorkflowMessage.builder()
                    .executionId("exec-B").topic("t").serviceName("s")
                    .status(WorkflowStatus.PENDING).build()));

            // Completing exec-A finds no tracked start -> zero duration, and crucially
            // the map could never have grown past its bound regardless of volume.
            bounded.onWorkflowMessage(new WorkflowMessageEvent(this, WorkflowMessage.builder()
                    .executionId("exec-A").topic("t").serviceName("s")
                    .status(WorkflowStatus.COMPLETED).build()));

            verify(metrics).recordWorkflowCompleted(eq("t"), eq("s"), eq(Duration.ZERO));
        }
    }
}

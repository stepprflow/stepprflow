package io.github.stepprflow.monitor.service;

import io.github.stepprflow.core.broker.MessageBroker;
import io.github.stepprflow.core.model.RetryInfo;
import io.github.stepprflow.core.model.WorkflowMessage;
import io.github.stepprflow.core.model.WorkflowStatus;
import io.github.stepprflow.monitor.model.WorkflowExecution;
import io.github.stepprflow.monitor.repository.WorkflowExecutionRepository;
import io.github.stepprflow.monitor.util.WorkflowMessageFactory;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RetrySchedulerService Tests")
class RetrySchedulerServiceTest {

    @Mock
    private WorkflowExecutionRepository repository;

    @Mock
    private MessageBroker messageBroker;

    @Mock
    private WorkflowMessageFactory messageFactory;

    @InjectMocks
    private RetrySchedulerService retrySchedulerService;

    @Captor
    private ArgumentCaptor<WorkflowMessage> messageCaptor;

    private WorkflowExecution testExecution;
    private WorkflowMessage testMessage;
    private Instant testCreatedAt;

    @BeforeEach
    void setUp() {
        testCreatedAt = Instant.now().minusSeconds(3600);

        RetryInfo retryInfo = RetryInfo.builder()
                .attempt(2)
                .maxAttempts(3)
                .nextRetryAt(Instant.now().minusSeconds(60))
                .lastError("Previous failure")
                .build();

        testExecution = WorkflowExecution.builder()
                .executionId("exec-123")
                .correlationId("corr-456")
                .topic("test-topic")
                .status(WorkflowStatus.RETRY_PENDING)
                .currentStep(2)
                .totalSteps(5)
                .payload(Map.of("key", "value"))
                .payloadType("java.util.Map")
                .securityContext("token-abc")
                .metadata(Map.of("user", "test-user"))
                .retryInfo(retryInfo)
                .createdAt(testCreatedAt)
                .build();

        testMessage = WorkflowMessage.builder()
                .executionId("exec-123")
                .correlationId("corr-456")
                .topic("test-topic")
                .currentStep(2)
                .totalSteps(5)
                .status(WorkflowStatus.IN_PROGRESS)
                .payload(Map.of("key", "value"))
                .payloadType("java.util.Map")
                .securityContext("token-abc")
                .metadata(Map.of("user", "test-user"))
                .retryInfo(retryInfo)
                .createdAt(testCreatedAt)
                .updatedAt(Instant.now())
                .build();
    }

    @Nested
    @DisplayName("processPendingRetries() method")
    class ProcessPendingRetriesTests {

        @Test
        @DisplayName("Should do nothing when no pending retries")
        void shouldDoNothingWhenNoPendingRetries() {
            when(repository.findPendingRetries(any(Instant.class))).thenReturn(List.of());

            retrySchedulerService.processPendingRetries();

            verify(messageBroker, never()).send(any(), any());
        }

        @Test
        @DisplayName("Should process single pending retry")
        void shouldProcessSinglePendingRetry() {
            when(repository.findPendingRetries(any(Instant.class))).thenReturn(List.of(testExecution));
            when(messageFactory.createRetryMessage(testExecution)).thenReturn(testMessage);

            retrySchedulerService.processPendingRetries();

            verify(messageBroker).send(eq("test-topic"), eq(testMessage));
            verify(messageFactory).createRetryMessage(testExecution);
        }

        @Test
        @DisplayName("Should process multiple pending retries")
        void shouldProcessMultiplePendingRetries() {
            WorkflowExecution execution2 = WorkflowExecution.builder()
                    .executionId("exec-456")
                    .topic("another-topic")
                    .currentStep(1)
                    .totalSteps(3)
                    .retryInfo(RetryInfo.builder().attempt(1).maxAttempts(3).build())
                    .build();

            WorkflowMessage message2 = WorkflowMessage.builder()
                    .executionId("exec-456")
                    .topic("another-topic")
                    .status(WorkflowStatus.IN_PROGRESS)
                    .build();

            when(repository.findPendingRetries(any(Instant.class)))
                    .thenReturn(List.of(testExecution, execution2));
            when(messageFactory.createRetryMessage(testExecution)).thenReturn(testMessage);
            when(messageFactory.createRetryMessage(execution2)).thenReturn(message2);

            retrySchedulerService.processPendingRetries();

            verify(messageBroker, times(2)).send(any(), any());
        }

        @Test
        @DisplayName("Should continue processing even if one retry fails")
        void shouldContinueProcessingEvenIfOneRetryFails() {
            WorkflowExecution execution2 = WorkflowExecution.builder()
                    .executionId("exec-456")
                    .topic("another-topic")
                    .currentStep(1)
                    .totalSteps(3)
                    .build();

            WorkflowMessage message2 = WorkflowMessage.builder()
                    .executionId("exec-456")
                    .topic("another-topic")
                    .status(WorkflowStatus.IN_PROGRESS)
                    .build();

            when(repository.findPendingRetries(any(Instant.class)))
                    .thenReturn(List.of(testExecution, execution2));
            when(messageFactory.createRetryMessage(testExecution)).thenReturn(testMessage);
            when(messageFactory.createRetryMessage(execution2)).thenReturn(message2);

            // First call throws exception, second succeeds
            doThrow(new RuntimeException("Kafka error"))
                    .doNothing()
                    .when(messageBroker).send(any(), any());

            retrySchedulerService.processPendingRetries();

            // Both should be attempted
            verify(messageBroker, times(2)).send(any(), any());
        }
    }

    @Nested
    @DisplayName("Retry message construction")
    class RetryMessageConstructionTests {

        @Test
        @DisplayName("Should delegate message creation to factory")
        void shouldDelegateMessageCreationToFactory() {
            when(repository.findPendingRetries(any(Instant.class))).thenReturn(List.of(testExecution));
            when(messageFactory.createRetryMessage(testExecution)).thenReturn(testMessage);

            retrySchedulerService.processPendingRetries();

            verify(messageFactory).createRetryMessage(testExecution);
            verify(messageBroker).send(eq("test-topic"), eq(testMessage));
        }

        @Test
        @DisplayName("Should send message returned by factory")
        void shouldSendMessageReturnedByFactory() {
            when(repository.findPendingRetries(any(Instant.class))).thenReturn(List.of(testExecution));
            when(messageFactory.createRetryMessage(testExecution)).thenReturn(testMessage);

            retrySchedulerService.processPendingRetries();

            verify(messageBroker).send(eq("test-topic"), eq(testMessage));
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle execution with null retry info")
        void shouldHandleExecutionWithNullRetryInfo() {
            testExecution.setRetryInfo(null);
            WorkflowMessage messageWithNullRetry = testMessage.toBuilder().retryInfo(null).build();
            when(repository.findPendingRetries(any(Instant.class))).thenReturn(List.of(testExecution));
            when(messageFactory.createRetryMessage(testExecution)).thenReturn(messageWithNullRetry);

            retrySchedulerService.processPendingRetries();

            verify(messageFactory).createRetryMessage(testExecution);
            verify(messageBroker).send(eq("test-topic"), eq(messageWithNullRetry));
        }

        @Test
        @DisplayName("Should handle execution with null metadata")
        void shouldHandleExecutionWithNullMetadata() {
            testExecution.setMetadata(null);
            WorkflowMessage messageWithNullMeta = testMessage.toBuilder().metadata(null).build();
            when(repository.findPendingRetries(any(Instant.class))).thenReturn(List.of(testExecution));
            when(messageFactory.createRetryMessage(testExecution)).thenReturn(messageWithNullMeta);

            retrySchedulerService.processPendingRetries();

            verify(messageFactory).createRetryMessage(testExecution);
            verify(messageBroker).send(any(), eq(messageWithNullMeta));
        }

        @Test
        @DisplayName("Should handle execution with null security context")
        void shouldHandleExecutionWithNullSecurityContext() {
            testExecution.setSecurityContext(null);
            WorkflowMessage messageWithNullSec = testMessage.toBuilder().securityContext(null).build();
            when(repository.findPendingRetries(any(Instant.class))).thenReturn(List.of(testExecution));
            when(messageFactory.createRetryMessage(testExecution)).thenReturn(messageWithNullSec);

            retrySchedulerService.processPendingRetries();

            verify(messageFactory).createRetryMessage(testExecution);
            verify(messageBroker).send(any(), eq(messageWithNullSec));
        }
    }
}
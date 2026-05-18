package io.github.stepprflow.broker.kafka;

import io.github.stepprflow.core.model.WorkflowMessage;
import io.github.stepprflow.core.model.WorkflowStatus;
import io.github.stepprflow.core.service.StepExecutor;
import io.github.stepprflow.core.service.WorkflowRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("KafkaMessageListener Tests")
class KafkaMessageListenerTest {

    @Mock
    private StepExecutor stepExecutor;

    @Mock
    private WorkflowRegistry registry;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private Acknowledgment acknowledgment;

    private KafkaMessageListener listener;

    @BeforeEach
    void setUp() {
        listener = new KafkaMessageListener(stepExecutor, registry, eventPublisher);
        // Default: the local registry knows about "test-topic"
        when(registry.getTopics()).thenReturn(List.of("test-topic"));
    }

    @Nested
    @DisplayName("onMessage()")
    class OnMessageTests {

        @Test
        @DisplayName("Should process PENDING message and acknowledge")
        void shouldProcessPendingMessage() {
            // Given
            WorkflowMessage message = createMessage(WorkflowStatus.PENDING);
            ConsumerRecord<String, WorkflowMessage> record = createRecord(message);

            // When
            listener.onMessage(record, acknowledgment);

            // Then
            verify(stepExecutor).execute(message);
            verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("Should publish message received event")
        void shouldPublishMessageReceivedEvent() {
            // Given
            WorkflowMessage message = createMessage(WorkflowStatus.PENDING);
            ConsumerRecord<String, WorkflowMessage> record = createRecord(message);

            // When
            listener.onMessage(record, acknowledgment);

            // Then
            verify(eventPublisher).publishEvent(any());
        }

        @Test
        @DisplayName("Should process IN_PROGRESS message and acknowledge")
        void shouldProcessInProgressMessage() {
            // Given
            WorkflowMessage message = createMessage(WorkflowStatus.IN_PROGRESS);
            ConsumerRecord<String, WorkflowMessage> record = createRecord(message);

            // When
            listener.onMessage(record, acknowledgment);

            // Then
            verify(stepExecutor).execute(message);
            verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("Should skip COMPLETED message and acknowledge")
        void shouldSkipCompletedMessage() {
            // Given
            WorkflowMessage message = createMessage(WorkflowStatus.COMPLETED);
            ConsumerRecord<String, WorkflowMessage> record = createRecord(message);

            // When
            listener.onMessage(record, acknowledgment);

            // Then
            verify(stepExecutor, never()).execute(any());
            verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("Should skip FAILED message and acknowledge")
        void shouldSkipFailedMessage() {
            // Given
            WorkflowMessage message = createMessage(WorkflowStatus.FAILED);
            ConsumerRecord<String, WorkflowMessage> record = createRecord(message);

            // When
            listener.onMessage(record, acknowledgment);

            // Then
            verify(stepExecutor, never()).execute(any());
            verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("Should skip CANCELLED message and acknowledge")
        void shouldSkipCancelledMessage() {
            // Given
            WorkflowMessage message = createMessage(WorkflowStatus.CANCELLED);
            ConsumerRecord<String, WorkflowMessage> record = createRecord(message);

            // When
            listener.onMessage(record, acknowledgment);

            // Then
            verify(stepExecutor, never()).execute(any());
            verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("Should acknowledge null message")
        void shouldAcknowledgeNullMessage() {
            // Given
            ConsumerRecord<String, WorkflowMessage> record = new ConsumerRecord<>(
                    "test-topic", 0, 0L, "key", null
            );

            // When
            listener.onMessage(record, acknowledgment);

            // Then
            verify(stepExecutor, never()).execute(any());
            verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("Should not acknowledge when executor throws exception")
        void shouldNotAcknowledgeOnException() {
            // Given
            WorkflowMessage message = createMessage(WorkflowStatus.PENDING);
            ConsumerRecord<String, WorkflowMessage> record = createRecord(message);
            doThrow(new RuntimeException("Processing failed")).when(stepExecutor).execute(message);

            // When
            listener.onMessage(record, acknowledgment);

            // Then
            verify(stepExecutor).execute(message);
            verify(acknowledgment, never()).acknowledge();
        }

        @Test
        @DisplayName("Should process message when topic is registered locally")
        void onMessage_withRegisteredTopic_processesMessage() {
            // Given
            WorkflowMessage message = createMessage(WorkflowStatus.PENDING);
            ConsumerRecord<String, WorkflowMessage> record = createRecord(message);
            // "test-topic" is already stubbed as known in setUp()

            // When
            listener.onMessage(record, acknowledgment);

            // Then
            verify(stepExecutor).execute(message);
            verify(eventPublisher).publishEvent(any());
            verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("Should silently acknowledge and skip message on unregistered topic")
        void onMessage_withUnregisteredTopic_acknowledgesAndSkips() {
            // Given — simulate a foreign topic (e.g. produced by cockpit-svc-sales)
            when(registry.getTopics()).thenReturn(List.of("known-topic"));
            WorkflowMessage message = createMessage(WorkflowStatus.PENDING);
            ConsumerRecord<String, WorkflowMessage> record = new ConsumerRecord<>(
                    "invoice-creation", 0, 0L, message.getExecutionId(), message
            );

            // When
            listener.onMessage(record, acknowledgment);

            // Then — no side-effects, just silent ack
            verify(stepExecutor, never()).execute(any());
            verify(eventPublisher, never()).publishEvent(any());
            verify(acknowledgment).acknowledge();
        }
    }

    private WorkflowMessage createMessage(WorkflowStatus status) {
        return WorkflowMessage.builder()
                .executionId(UUID.randomUUID().toString())
                .correlationId(UUID.randomUUID().toString())
                .topic("test-topic")
                .currentStep(1)
                .totalSteps(3)
                .status(status)
                .payload(Map.of("key", "value"))
                .build();
    }

    private ConsumerRecord<String, WorkflowMessage> createRecord(WorkflowMessage message) {
        return new ConsumerRecord<>(
                message.getTopic(),
                0,
                0L,
                message.getExecutionId(),
                message
        );
    }
}

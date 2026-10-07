package io.github.stepprflow.core.service;

import io.github.stepprflow.core.StepprFlowProperties;
import io.github.stepprflow.core.broker.MessageBroker;
import io.github.stepprflow.core.model.StepDefinition;
import io.github.stepprflow.core.model.WorkflowDefinition;
import io.github.stepprflow.core.model.WorkflowMessage;
import io.github.stepprflow.core.security.SecurityContextPropagator;
import io.github.stepprflow.core.security.SecurityContextSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowStarterImpl Security Tests")
class WorkflowStarterImplSecurityTest {

    @Mock
    private WorkflowRegistry registry;

    @Mock
    private MessageBroker messageBroker;

    @Mock
    private SecurityContextPropagator securityContextPropagator;

    private WorkflowStarterImpl workflowStarter;

    @Captor
    private ArgumentCaptor<WorkflowMessage> messageCaptor;

    private WorkflowDefinition testDefinition;

    @BeforeEach
    void setUp() {
        workflowStarter = new WorkflowStarterImpl(
                registry,
                messageBroker,
                securityContextPropagator,
                new SecurityContextSigner(new StepprFlowProperties()),
                "test-service"
        );

        testDefinition = WorkflowDefinition.builder()
                .topic("test-topic")
                .description("Test workflow")
                .steps(List.of(
                        StepDefinition.builder().id(1).build(),
                        StepDefinition.builder().id(2).build()
                ))
                .partitions(1)
                .replication((short) 1)
                .build();
    }

    @Nested
    @DisplayName("Security context capture")
    class SecurityContextCaptureTests {

        @Test
        @DisplayName("Should capture security context on start()")
        void shouldCaptureSecurityContextOnStart() {
            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);
            when(securityContextPropagator.capture()).thenReturn("jwt-token-123");

            workflowStarter.start("test-topic", new TestPayload("data"));

            verify(securityContextPropagator).capture();
            verify(messageBroker).send(eq("test-topic"), messageCaptor.capture());
            WorkflowMessage message = messageCaptor.getValue();

            assertThat(message.getSecurityContext()).isEqualTo("jwt-token-123");
        }

        @Test
        @DisplayName("Should capture security context on start() with metadata")
        void shouldCaptureSecurityContextOnStartWithMetadata() {
            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);
            when(securityContextPropagator.capture()).thenReturn("jwt-token-456");

            workflowStarter.start("test-topic", new TestPayload("data"), Map.of("key", "value"));

            verify(securityContextPropagator).capture();
            verify(messageBroker).send(eq("test-topic"), messageCaptor.capture());
            WorkflowMessage message = messageCaptor.getValue();

            assertThat(message.getSecurityContext()).isEqualTo("jwt-token-456");
        }

        @Test
        @DisplayName("Should capture security context on startAndGetMessage()")
        void shouldCaptureSecurityContextOnStartAndGetMessage() {
            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);
            when(securityContextPropagator.capture()).thenReturn("jwt-token-789");

            WorkflowMessage message = workflowStarter.startAndGetMessage("test-topic", new TestPayload("data"));

            verify(securityContextPropagator).capture();
            assertThat(message.getSecurityContext()).isEqualTo("jwt-token-789");
        }

        @Test
        @DisplayName("Should handle null security context")
        void shouldHandleNullSecurityContext() {
            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);
            when(securityContextPropagator.capture()).thenReturn(null);

            workflowStarter.start("test-topic", new TestPayload("data"));

            verify(messageBroker).send(eq("test-topic"), messageCaptor.capture());
            WorkflowMessage message = messageCaptor.getValue();

            assertThat(message.getSecurityContext()).isNull();
        }

        @Test
        @DisplayName("Should capture security context on forward()")
        void shouldCaptureSecurityContextOnForward() {
            when(securityContextPropagator.capture()).thenReturn("jwt-token-forward");

            workflowStarter.forward("remote-topic", new TestPayload("data"));

            verify(securityContextPropagator).capture();
            verify(messageBroker).send(eq("remote-topic"), messageCaptor.capture());
            WorkflowMessage message = messageCaptor.getValue();

            assertThat(message.getSecurityContext()).isEqualTo("jwt-token-forward");
        }

        @Test
        @DisplayName("Should capture security context on forward() with metadata")
        void shouldCaptureSecurityContextOnForwardWithMetadata() {
            when(securityContextPropagator.capture()).thenReturn("jwt-token-forward-meta");

            workflowStarter.forward("remote-topic", new TestPayload("data"), Map.of("key", "value"));

            verify(securityContextPropagator).capture();
            verify(messageBroker).send(eq("remote-topic"), messageCaptor.capture());
            WorkflowMessage message = messageCaptor.getValue();

            assertThat(message.getSecurityContext()).isEqualTo("jwt-token-forward-meta");
        }
    }

    @Nested
    @DisplayName("SF-11: startAsync captures on the caller's thread")
    class AsyncContextCaptureTests {

        @Test
        @DisplayName("startAsync captures the security context on the caller's thread, "
                + "not the async worker thread")
        void startAsyncCapturesOnCallerThread() throws Exception {
            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);
            Thread callerThread = Thread.currentThread();
            java.util.concurrent.atomic.AtomicReference<Thread> captureThread =
                    new java.util.concurrent.atomic.AtomicReference<>();
            when(securityContextPropagator.capture()).thenAnswer(invocation -> {
                captureThread.set(Thread.currentThread());
                return "jwt-async";
            });

            workflowStarter.startAsync("test-topic", new TestPayload("data"))
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);

            // The capture must happen on the caller's thread; capturing inside
            // the CompletableFuture worker would read an empty/foreign context.
            assertThat(captureThread.get()).isSameAs(callerThread);
            verify(messageBroker).send(eq("test-topic"), messageCaptor.capture());
            assertThat(messageCaptor.getValue().getSecurityContext()).isEqualTo("jwt-async");
        }
    }

    // Test payload class
    record TestPayload(String data) {
    }
}
package io.github.stepprflow.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stepprflow.core.StepprFlowProperties;
import io.github.stepprflow.core.broker.MessageBroker;
import io.github.stepprflow.core.exception.MessageSendException;
import io.github.stepprflow.core.model.*;
import io.github.stepprflow.core.security.ForgedSecurityContextException;
import io.github.stepprflow.core.security.SecurityContextPropagator;
import io.github.stepprflow.core.security.SecurityContextSigner;
import io.github.stepprflow.core.security.TrustedClassResolver;
import io.github.stepprflow.core.security.UntrustedPayloadTypeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StepExecutor Tests")
class StepExecutorTest {

    @Mock
    private WorkflowRegistry registry;

    @Mock
    private MessageBroker messageBroker;

    @Mock
    private StepprFlowProperties properties;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private BackoffCalculator backoffCalculator;

    @Mock
    private CallbackMethodInvoker callbackMethodInvoker;

    @Mock
    private SecurityContextPropagator securityContextPropagator;

    @Mock
    private TrustedClassResolver trustedClassResolver;

    @Mock
    private SecurityContextSigner securityContextSigner;

    @InjectMocks
    private StepExecutor stepExecutor;

    @Captor
    private ArgumentCaptor<WorkflowMessage> messageCaptor;

    @Captor
    private ArgumentCaptor<String> topicCaptor;

    private WorkflowMessage testMessage;
    private WorkflowDefinition testDefinition;
    private TestWorkflow testWorkflow;

    @BeforeEach
    void setUp() {
        testWorkflow = new TestWorkflow();

        // Ne pas mettre payloadType pour éviter l'appel à objectMapper.convertValue()
        // Le payload brut sera utilisé directement par deserializePayload()
        testMessage = WorkflowMessage.builder()
                .executionId("exec-123")
                .correlationId("corr-456")
                .topic("test-topic")
                .currentStep(1)
                .totalSteps(3)
                .status(WorkflowStatus.IN_PROGRESS)
                .payload(Map.of("key", "value"))
                .createdAt(Instant.now())
                .build();

        // By default the signer is a pass-through (signing disabled): it returns
        // the stored context unchanged so restore() sees the original value.
        lenient().when(securityContextSigner.unwrapAndVerify(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

    @Nested
    @DisplayName("execute() method")
    class ExecuteTests {

        @Test
        @DisplayName("Should skip execution when workflow definition is not found")
        void shouldSkipExecutionWhenDefinitionNotFound() {
            when(registry.getDefinition("test-topic")).thenReturn(null);

            stepExecutor.execute(testMessage);

            verify(messageBroker, never()).sendSync(any(), any());
        }

        @Test
        @DisplayName("Should skip execution when step is not found")
        void shouldSkipExecutionWhenStepNotFound() throws Exception {
            // Le message a currentStep=1, mais on définit seulement le step 99
            // Donc le step 1 ne sera pas trouvé
            StepDefinition step = createStepDefinition(99, "step1");
            testDefinition = createWorkflowDefinition(List.of(step));

            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            stepExecutor.execute(testMessage);

            verify(messageBroker, never()).sendSync(any(), any());
        }

        @Test
        @DisplayName("Should execute step and advance to next step")
        void shouldExecuteStepAndAdvanceToNextStep() throws Exception {
            StepDefinition step1 = createStepDefinition(1, "step1");
            StepDefinition step2 = createStepDefinition(2, "step2");
            StepDefinition step3 = createStepDefinition(3, "step3");
            testDefinition = createWorkflowDefinition(List.of(step1, step2, step3));

            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            stepExecutor.execute(testMessage);

            verify(messageBroker).sendSync(eq("test-topic"), messageCaptor.capture());
            WorkflowMessage sentMessage = messageCaptor.getValue();

            assertThat(sentMessage.getCurrentStep()).isEqualTo(2);
            assertThat(sentMessage.getStatus()).isEqualTo(WorkflowStatus.IN_PROGRESS);
            assertThat(testWorkflow.step1Called).isTrue();
        }

        @Test
        @DisplayName("Should complete workflow on last step")
        void shouldCompleteWorkflowOnLastStep() throws Exception {
            testMessage = testMessage.toBuilder()
                    .currentStep(3)
                    .build();

            StepDefinition step1 = createStepDefinition(1, "step1");
            StepDefinition step2 = createStepDefinition(2, "step2");
            StepDefinition step3 = createStepDefinition(3, "step3");
            testDefinition = createWorkflowDefinition(List.of(step1, step2, step3));

            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            stepExecutor.execute(testMessage);

            verify(messageBroker).sendSync(eq("test-topic.completed"), messageCaptor.capture());
            WorkflowMessage sentMessage = messageCaptor.getValue();

            assertThat(sentMessage.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        }

        @Test
        @DisplayName("Should call success callback on completion")
        void shouldCallSuccessCallbackOnCompletion() throws Exception {
            testMessage = testMessage.toBuilder()
                    .currentStep(3)
                    .build();

            StepDefinition step1 = createStepDefinition(1, "step1");
            StepDefinition step2 = createStepDefinition(2, "step2");
            StepDefinition step3 = createStepDefinition(3, "step3");
            Method onSuccessMethod = TestWorkflow.class.getDeclaredMethod("onSuccess", Object.class);
            testDefinition = createWorkflowDefinition(List.of(step1, step2, step3), onSuccessMethod, null);

            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            stepExecutor.execute(testMessage);

            verify(callbackMethodInvoker).invokeRaw(eq(onSuccessMethod), eq(testWorkflow), any(WorkflowMessage.class), isNull());
        }

        @Test
        @DisplayName("Should execute step with null payload")
        void shouldExecuteStepWithNullPayload() throws Exception {
            testMessage = testMessage.toBuilder()
                    .payload(null)
                    .build();

            StepDefinition step1 = createStepDefinition(1, "step1");
            StepDefinition step2 = createStepDefinition(2, "step2");
            StepDefinition step3 = createStepDefinition(3, "step3");
            testDefinition = createWorkflowDefinition(List.of(step1, step2, step3));

            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            stepExecutor.execute(testMessage);

            verify(messageBroker).sendSync(eq("test-topic"), messageCaptor.capture());
            assertThat(testWorkflow.step1Called).isTrue();
        }

        @Test
        @DisplayName("Should use raw payload when payloadType is null")
        void shouldUseRawPayloadWhenPayloadTypeIsNull() throws Exception {
            // Payload is NOT null but payloadType IS null -> should use raw payload
            Map<String, Object> rawPayload = Map.of("rawKey", "rawValue");
            testMessage = testMessage.toBuilder()
                    .payload(rawPayload)
                    .payloadType(null)
                    .build();

            StepDefinition step1 = createStepDefinition(1, "step1");
            StepDefinition step2 = createStepDefinition(2, "step2");
            StepDefinition step3 = createStepDefinition(3, "step3");
            testDefinition = createWorkflowDefinition(List.of(step1, step2, step3));

            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            stepExecutor.execute(testMessage);

            // Verify step was called - if raw payload was null, step would fail
            assertThat(testWorkflow.step1Called).isTrue();
            assertThat(testWorkflow.lastPayload).isEqualTo(rawPayload);
            // ObjectMapper should NOT be called when payloadType is null
            verify(objectMapper, never()).convertValue(any(), any(Class.class));
        }

        @Test
        @DisplayName("Should call success callback with no parameters")
        void shouldCallSuccessCallbackWithNoParameters() throws Exception {
            testMessage = testMessage.toBuilder()
                    .currentStep(3)
                    .build();

            StepDefinition step1 = createStepDefinition(1, "step1");
            StepDefinition step2 = createStepDefinition(2, "step2");
            StepDefinition step3 = createStepDefinition(3, "step3");
            Method onSuccessMethod = TestWorkflow.class.getDeclaredMethod("onSuccessNoParams");
            testDefinition = createWorkflowDefinition(List.of(step1, step2, step3), onSuccessMethod, null);

            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            stepExecutor.execute(testMessage);

            verify(callbackMethodInvoker).invokeRaw(eq(onSuccessMethod), eq(testWorkflow), any(WorkflowMessage.class), isNull());
        }

        @Test
        @DisplayName("Should call success callback with WorkflowMessage parameter")
        void shouldCallSuccessCallbackWithWorkflowMessage() throws Exception {
            testMessage = testMessage.toBuilder()
                    .currentStep(3)
                    .build();

            StepDefinition step1 = createStepDefinition(1, "step1");
            StepDefinition step2 = createStepDefinition(2, "step2");
            StepDefinition step3 = createStepDefinition(3, "step3");
            Method onSuccessMethod = TestWorkflow.class.getDeclaredMethod("onSuccessWithMessage", WorkflowMessage.class);
            testDefinition = createWorkflowDefinition(List.of(step1, step2, step3), onSuccessMethod, null);

            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            stepExecutor.execute(testMessage);

            verify(callbackMethodInvoker).invokeRaw(eq(onSuccessMethod), eq(testWorkflow), any(WorkflowMessage.class), isNull());
        }
    }

    @Nested
    @DisplayName("Failure handling")
    class FailureHandlingTests {

        private StepprFlowProperties.Retry retryConfig;
        private StepprFlowProperties.Dlq dlqConfig;

        @BeforeEach
        void setUpRetryProperties() {
            retryConfig = new StepprFlowProperties.Retry();
            retryConfig.setMaxAttempts(3);
            retryConfig.setInitialDelay(Duration.ofSeconds(1));
            retryConfig.setMaxDelay(Duration.ofMinutes(5));
            retryConfig.setMultiplier(2.0);
            retryConfig.setNonRetryableExceptions(List.of("java.lang.IllegalArgumentException"));

            dlqConfig = new StepprFlowProperties.Dlq();
            dlqConfig.setEnabled(true);
            dlqConfig.setSuffix(".dlq");

            // Configure backoff calculator mock (lenient because not all tests trigger retry)
            lenient().when(backoffCalculator.calculate(anyInt())).thenReturn(Duration.ofSeconds(1));
        }

        @Test
        @DisplayName("Should schedule retry on retryable exception")
        void shouldScheduleRetryOnRetryableException() throws Exception {
            when(properties.getRetry()).thenReturn(retryConfig);

            StepDefinition step = createFailingStepDefinition(1, "failingStep");
            testDefinition = createWorkflowDefinition(List.of(step));

            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            stepExecutor.execute(testMessage);

            verify(messageBroker).sendSync(eq("test-topic.retry"), messageCaptor.capture());
            WorkflowMessage retryMessage = messageCaptor.getValue();

            assertThat(retryMessage.getStatus()).isEqualTo(WorkflowStatus.RETRY_PENDING);
            assertThat(retryMessage.getRetryInfo()).isNotNull();
            // Le code crée RetryInfo avec attempt=1 puis appelle nextAttempt() qui incrémente à 2
            assertThat(retryMessage.getRetryInfo().getAttempt()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should send to DLQ when retries exhausted")
        void shouldSendToDlqWhenRetriesExhausted() throws Exception {
            // Seulement getDlq() est utilisé car isExhausted() court-circuite l'appel à getRetry()
            when(properties.getDlq()).thenReturn(dlqConfig);

            RetryInfo exhaustedRetry = RetryInfo.builder()
                    .attempt(3)
                    .maxAttempts(3)
                    .build();
            testMessage = testMessage.toBuilder()
                    .retryInfo(exhaustedRetry)
                    .build();

            StepDefinition step = createFailingStepDefinition(1, "failingStep");
            testDefinition = createWorkflowDefinition(List.of(step));

            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            stepExecutor.execute(testMessage);

            verify(messageBroker).sendSync(eq("test-topic.dlq"), messageCaptor.capture());
            WorkflowMessage dlqMessage = messageCaptor.getValue();

            assertThat(dlqMessage.getStatus()).isEqualTo(WorkflowStatus.FAILED);
            assertThat(dlqMessage.getErrorInfo()).isNotNull();
            assertThat(dlqMessage.getErrorInfo().getCode()).isEqualTo("STEP_EXECUTION_FAILED");
        }

        @Test
        @DisplayName("Should send to DLQ on non-retryable exception")
        void shouldSendToDlqOnNonRetryableException() throws Exception {
            when(properties.getRetry()).thenReturn(retryConfig);
            when(properties.getDlq()).thenReturn(dlqConfig);

            StepDefinition step = createIllegalArgumentStepDefinition(1, "illegalStep");
            testDefinition = createWorkflowDefinition(List.of(step));

            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            stepExecutor.execute(testMessage);

            verify(messageBroker).sendSync(eq("test-topic.dlq"), any(WorkflowMessage.class));
        }

        @Test
        @DisplayName("Should continue on failure when configured")
        void shouldContinueOnFailureWhenConfigured() throws Exception {
            // Pas besoin de configurer properties car continueOnFailure court-circuite le retry
            StepDefinition step1 = createFailingStepDefinition(1, "failingStep", true);
            StepDefinition step2 = createStepDefinition(2, "step2");
            testDefinition = createWorkflowDefinition(List.of(step1, step2));

            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            stepExecutor.execute(testMessage);

            verify(messageBroker).sendSync(eq("test-topic"), messageCaptor.capture());
            WorkflowMessage nextMessage = messageCaptor.getValue();

            assertThat(nextMessage.getCurrentStep()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should call failure callback on DLQ")
        void shouldCallFailureCallbackOnDlq() throws Exception {
            when(properties.getDlq()).thenReturn(dlqConfig);

            RetryInfo exhaustedRetry = RetryInfo.builder()
                    .attempt(3)
                    .maxAttempts(3)
                    .build();
            testMessage = testMessage.toBuilder()
                    .retryInfo(exhaustedRetry)
                    .build();

            StepDefinition step = createFailingStepDefinition(1, "failingStep");
            Method onFailureMethod = TestWorkflow.class.getDeclaredMethod("onFailure", Object.class, Throwable.class);
            testDefinition = createWorkflowDefinition(List.of(step), null, onFailureMethod);

            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            stepExecutor.execute(testMessage);

            verify(callbackMethodInvoker).invokeRaw(eq(onFailureMethod), eq(testWorkflow), any(WorkflowMessage.class), any(Throwable.class));
        }

        @Test
        @DisplayName("Should not send to DLQ when DLQ is disabled")
        void shouldNotSendToDlqWhenDisabled() throws Exception {
            dlqConfig.setEnabled(false);
            when(properties.getDlq()).thenReturn(dlqConfig);

            RetryInfo exhaustedRetry = RetryInfo.builder()
                    .attempt(3)
                    .maxAttempts(3)
                    .build();
            testMessage = testMessage.toBuilder()
                    .retryInfo(exhaustedRetry)
                    .build();

            StepDefinition step = createFailingStepDefinition(1, "failingStep");
            testDefinition = createWorkflowDefinition(List.of(step));

            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            stepExecutor.execute(testMessage);

            // Should not send to DLQ when disabled
            verify(messageBroker, never()).sendSync(eq("test-topic.dlq"), any());
        }

        @Test
        @DisplayName("Should truncate long stack traces")
        void shouldTruncateLongStackTraces() throws Exception {
            when(properties.getDlq()).thenReturn(dlqConfig);

            RetryInfo exhaustedRetry = RetryInfo.builder()
                    .attempt(3)
                    .maxAttempts(3)
                    .build();
            testMessage = testMessage.toBuilder()
                    .retryInfo(exhaustedRetry)
                    .build();

            // Use a step that throws an exception with deep stack trace
            StepDefinition step = createDeepStackTraceStepDefinition(1, "deepStackStep");
            testDefinition = createWorkflowDefinition(List.of(step));

            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            stepExecutor.execute(testMessage);

            verify(messageBroker).sendSync(eq("test-topic.dlq"), messageCaptor.capture());
            WorkflowMessage dlqMessage = messageCaptor.getValue();

            // Stack trace should be truncated to 2000 chars + "..."
            assertThat(dlqMessage.getErrorInfo().getStackTrace()).hasSizeLessThanOrEqualTo(2003);
            if (dlqMessage.getErrorInfo().getStackTrace().length() > 2000) {
                assertThat(dlqMessage.getErrorInfo().getStackTrace()).endsWith("...");
            }
        }
    }

    @Nested
    @DisplayName("Backoff calculation")
    class BackoffCalculationTests {

        @BeforeEach
        void setUpRetryProperties() {
            StepprFlowProperties.Retry retryConfig = new StepprFlowProperties.Retry();
            retryConfig.setMaxAttempts(5);
            retryConfig.setInitialDelay(Duration.ofSeconds(1));
            retryConfig.setMaxDelay(Duration.ofMinutes(5));
            retryConfig.setMultiplier(2.0);
            retryConfig.setNonRetryableExceptions(List.of());

            when(properties.getRetry()).thenReturn(retryConfig);
            // Configure backoff calculator mock
            when(backoffCalculator.calculate(anyInt())).thenReturn(Duration.ofSeconds(2));
        }

        @Test
        @DisplayName("Should apply exponential backoff on retries")
        void shouldApplyExponentialBackoff() throws Exception {
            // Test with attempt 1
            testMessage = testMessage.toBuilder()
                    .retryInfo(RetryInfo.builder().attempt(1).maxAttempts(5).build())
                    .build();

            StepDefinition step = createFailingStepDefinition(1, "failingStep");
            testDefinition = createWorkflowDefinition(List.of(step));

            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            stepExecutor.execute(testMessage);

            verify(messageBroker).sendSync(eq("test-topic.retry"), messageCaptor.capture());
            WorkflowMessage retryMessage = messageCaptor.getValue();

            assertThat(retryMessage.getRetryInfo().getNextRetryAt()).isNotNull();
            assertThat(retryMessage.getRetryInfo().getAttempt()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("security context restoration (SF-1)")
    class SecurityContextRestoreTests {

        private StepprFlowProperties.Dlq dlqConfig;

        @BeforeEach
        void setUpDlq() {
            dlqConfig = new StepprFlowProperties.Dlq();
            dlqConfig.setEnabled(true);
            dlqConfig.setSuffix(".dlq");
        }

        @Test
        @DisplayName("Should DLQ (not retry, not propagate, not execute the step) when restore fails")
        void shouldDlqAndNotRetryWhenRestoreFails() throws Exception {
            when(properties.getDlq()).thenReturn(dlqConfig);
            testMessage = testMessage.toBuilder()
                    .securityContext("expired-jwt")
                    .build();

            StepDefinition step1 = createStepDefinition(1, "step1");
            testDefinition = createWorkflowDefinition(List.of(step1));
            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            // A fail-close propagator throws when the embedded credential is no
            // longer valid (e.g. expired JWT). This must not escape execute().
            doThrow(new RuntimeException("Jwt expired"))
                    .when(securityContextPropagator).restore(anyString());

            // Must NOT propagate: a thrown exception here would leave the broker
            // message un-acked and redelivered forever (poison-pill).
            stepExecutor.execute(testMessage);

            // Routed straight to DLQ as a terminal, non-retryable failure.
            verify(messageBroker).sendSync(eq("test-topic.dlq"), messageCaptor.capture());
            WorkflowMessage dlqMessage = messageCaptor.getValue();
            assertThat(dlqMessage.getStatus()).isEqualTo(WorkflowStatus.FAILED);
            assertThat(dlqMessage.getErrorInfo()).isNotNull();
            assertThat(dlqMessage.getErrorInfo().getCode())
                    .isEqualTo("SECURITY_CONTEXT_RESTORE_FAILED");

            // Never retried; the step handler is never invoked.
            verify(messageBroker, never()).sendSync(eq("test-topic.retry"), any());
            assertThat(testWorkflow.step1Called).isFalse();
        }

        @Test
        @DisplayName("Should clear the security context even when restore fails")
        void shouldClearContextWhenRestoreFails() throws Exception {
            when(properties.getDlq()).thenReturn(dlqConfig);
            testMessage = testMessage.toBuilder()
                    .securityContext("expired-jwt")
                    .build();

            StepDefinition step1 = createStepDefinition(1, "step1");
            testDefinition = createWorkflowDefinition(List.of(step1));
            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            doThrow(new RuntimeException("Jwt expired"))
                    .when(securityContextPropagator).restore(anyString());

            stepExecutor.execute(testMessage);

            // clear() must run on the failing path too, or the restored context
            // leaks onto the pooled consumer thread (cross-identity bleed).
            InOrder inOrder = inOrder(securityContextPropagator);
            inOrder.verify(securityContextPropagator).restore("expired-jwt");
            inOrder.verify(securityContextPropagator).clear();
        }

        @Test
        @DisplayName("Should not retry, throw or execute the step when restore fails and DLQ is disabled")
        void shouldNotRetryOrThrowWhenRestoreFailsAndDlqDisabled() throws Exception {
            dlqConfig.setEnabled(false);
            when(properties.getDlq()).thenReturn(dlqConfig);
            testMessage = testMessage.toBuilder()
                    .securityContext("expired-jwt")
                    .build();

            StepDefinition step1 = createStepDefinition(1, "step1");
            testDefinition = createWorkflowDefinition(List.of(step1));
            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            doThrow(new RuntimeException("Jwt expired"))
                    .when(securityContextPropagator).restore(anyString());

            // Even with DLQ off, the restore failure must still not poison-pill the
            // broker: no exception escapes, nothing is retried, the step never runs,
            // and the context is cleared.
            stepExecutor.execute(testMessage);

            verify(messageBroker, never()).sendSync(eq("test-topic.dlq"), any());
            verify(messageBroker, never()).sendSync(eq("test-topic.retry"), any());
            verify(securityContextPropagator).clear();
            assertThat(testWorkflow.step1Called).isFalse();
        }

        @Test
        @DisplayName("Should clear the security context after a successful step")
        void shouldClearContextAfterSuccessfulStep() throws Exception {
            testMessage = testMessage.toBuilder()
                    .securityContext("valid-jwt")
                    .build();

            StepDefinition step1 = createStepDefinition(1, "step1");
            StepDefinition step2 = createStepDefinition(2, "step2");
            testDefinition = createWorkflowDefinition(List.of(step1, step2));
            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            stepExecutor.execute(testMessage);

            InOrder inOrder = inOrder(securityContextPropagator);
            inOrder.verify(securityContextPropagator).restore("valid-jwt");
            inOrder.verify(securityContextPropagator).clear();
            assertThat(testWorkflow.step1Called).isTrue();
        }
    }

    @Nested
    @DisplayName("payloadType trust enforcement (SF-2)")
    class PayloadTypeSecurityTests {

        private StepprFlowProperties.Retry retryConfig;
        private StepprFlowProperties.Dlq dlqConfig;

        @BeforeEach
        void setUpProps() {
            retryConfig = new StepprFlowProperties.Retry();
            retryConfig.setMaxAttempts(3);
            retryConfig.setNonRetryableExceptions(List.of());
            dlqConfig = new StepprFlowProperties.Dlq();
            dlqConfig.setEnabled(true);
            dlqConfig.setSuffix(".dlq");
        }

        @Test
        @DisplayName("Should DLQ (not retry, not execute the step) when payloadType is untrusted")
        void shouldDlqWhenPayloadTypeUntrusted() throws Exception {
            when(properties.getRetry()).thenReturn(retryConfig);
            when(properties.getDlq()).thenReturn(dlqConfig);
            testMessage = testMessage.toBuilder()
                    .payloadType("com.evil.Gadget")
                    .build();

            StepDefinition step1 = createStepDefinition(1, "step1");
            testDefinition = createWorkflowDefinition(List.of(step1));
            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            // The resolver rejects the attacker-controlled type before any class
            // is loaded.
            when(trustedClassResolver.loadTrustedClass("com.evil.Gadget"))
                    .thenThrow(new UntrustedPayloadTypeException(
                            "com.evil.Gadget",
                            List.of("io.github.stepprflow.core.model")));

            stepExecutor.execute(testMessage);

            // Terminal, non-retryable: straight to DLQ, step never runs, no retry.
            verify(messageBroker).sendSync(eq("test-topic.dlq"), any(WorkflowMessage.class));
            verify(messageBroker, never()).sendSync(eq("test-topic.retry"), any());
            assertThat(testWorkflow.step1Called).isFalse();
        }
    }

    @Nested
    @DisplayName("security context integrity (SF-3)")
    class SecurityContextIntegrityTests {

        private StepprFlowProperties.Dlq dlqConfig;

        @BeforeEach
        void setUpDlq() {
            dlqConfig = new StepprFlowProperties.Dlq();
            dlqConfig.setEnabled(true);
            dlqConfig.setSuffix(".dlq");
        }

        @Test
        @DisplayName("Should DLQ (not retry, not restore, not execute) when the context signature is invalid")
        void shouldDlqWhenContextForged() throws Exception {
            when(properties.getDlq()).thenReturn(dlqConfig);
            testMessage = testMessage.toBuilder()
                    .securityContext("forged.context")
                    .build();

            StepDefinition step1 = createStepDefinition(1, "step1");
            testDefinition = createWorkflowDefinition(List.of(step1));
            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            // The signer rejects the tampered/forged envelope before restore.
            when(securityContextSigner.unwrapAndVerify(any(), any(), eq("forged.context")))
                    .thenThrow(new ForgedSecurityContextException(
                            "exec-123", "signature mismatch"));

            stepExecutor.execute(testMessage);

            verify(messageBroker).sendSync(eq("test-topic.dlq"), any(WorkflowMessage.class));
            verify(messageBroker, never()).sendSync(eq("test-topic.retry"), any());
            verify(securityContextPropagator, never()).restore(any());
            assertThat(testWorkflow.step1Called).isFalse();
        }
    }

    @Nested
    @DisplayName("confirmed sends — no silent message loss (SF-4)")
    class ConfirmedSendTests {

        @Test
        @DisplayName("Should use a confirmed (sync) send to advance to the next step")
        void shouldUseConfirmedSendForAdvance() throws Exception {
            StepDefinition step1 = createStepDefinition(1, "step1");
            StepDefinition step2 = createStepDefinition(2, "step2");
            testDefinition = createWorkflowDefinition(List.of(step1, step2));
            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            stepExecutor.execute(testMessage);

            verify(messageBroker).sendSync(eq("test-topic"), any(WorkflowMessage.class));
            // The fire-and-forget send is never used for workflow production.
            verify(messageBroker, never()).send(any(), any());
        }

        @Test
        @DisplayName("Should propagate (so the record is not acked) when a confirmed send fails")
        void shouldPropagateWhenConfirmedSendFails() throws Exception {
            StepprFlowProperties.Retry retryConfig = new StepprFlowProperties.Retry();
            retryConfig.setMaxAttempts(3);
            retryConfig.setNonRetryableExceptions(List.of());
            when(properties.getRetry()).thenReturn(retryConfig);
            lenient().when(backoffCalculator.calculate(anyInt()))
                    .thenReturn(Duration.ofSeconds(1));

            StepDefinition step1 = createStepDefinition(1, "step1");
            StepDefinition step2 = createStepDefinition(2, "step2");
            testDefinition = createWorkflowDefinition(List.of(step1, step2));
            when(registry.getDefinition("test-topic")).thenReturn(testDefinition);

            // Broker unreachable: every confirmed send throws.
            doThrow(new MessageSendException("kafka", "test-topic", "exec-123",
                    "broker down", null))
                    .when(messageBroker).sendSync(any(), any());

            // The failure must escape execute() so the Kafka listener does NOT
            // acknowledge the record (it is redelivered instead of being lost).
            assertThatThrownBy(() -> stepExecutor.execute(testMessage))
                    .isInstanceOf(MessageSendException.class);
            assertThat(testWorkflow.step1Called).isTrue();
        }
    }

    // Helper methods
    private StepDefinition createStepDefinition(int id, String methodName) throws Exception {
        Method method = TestWorkflow.class.getDeclaredMethod(methodName, Object.class);
        return StepDefinition.builder()
                .id(id)
                .label(methodName)
                .description("Test step " + id)
                .method(method)
                .skippable(false)
                .continueOnFailure(false)
                .build();
    }

    private StepDefinition createFailingStepDefinition(int id, String methodName) throws Exception {
        return createFailingStepDefinition(id, methodName, false);
    }

    private StepDefinition createFailingStepDefinition(int id, String methodName, boolean continueOnFailure) throws Exception {
        Method method = TestWorkflow.class.getDeclaredMethod(methodName, Object.class);
        return StepDefinition.builder()
                .id(id)
                .label(methodName)
                .description("Failing step " + id)
                .method(method)
                .skippable(false)
                .continueOnFailure(continueOnFailure)
                .build();
    }

    private StepDefinition createIllegalArgumentStepDefinition(int id, String methodName) throws Exception {
        Method method = TestWorkflow.class.getDeclaredMethod(methodName, Object.class);
        return StepDefinition.builder()
                .id(id)
                .label(methodName)
                .description("Illegal argument step " + id)
                .method(method)
                .skippable(false)
                .continueOnFailure(false)
                .build();
    }

    private StepDefinition createDeepStackTraceStepDefinition(int id, String methodName) throws Exception {
        Method method = TestWorkflow.class.getDeclaredMethod(methodName, Object.class);
        return StepDefinition.builder()
                .id(id)
                .label(methodName)
                .description("Deep stack trace step " + id)
                .method(method)
                .skippable(false)
                .continueOnFailure(false)
                .build();
    }

    private WorkflowDefinition createWorkflowDefinition(List<StepDefinition> steps) {
        return createWorkflowDefinition(steps, null, null);
    }

    private WorkflowDefinition createWorkflowDefinition(List<StepDefinition> steps,
                                                        Method onSuccessMethod,
                                                        Method onFailureMethod) {
        return WorkflowDefinition.builder()
                .topic("test-topic")
                .description("Test workflow")
                .handler(testWorkflow)
                .handlerClass(TestWorkflow.class)
                .steps(steps)
                .onSuccessMethod(onSuccessMethod)
                .onFailureMethod(onFailureMethod)
                .partitions(1)
                .replication((short) 1)
                .build();
    }

    // Test workflow class
    static class TestWorkflow implements StepprFlow {
        boolean step1Called = false;
        boolean step2Called = false;
        boolean step3Called = false;
        boolean successCalled = false;
        boolean successNoParamsCalled = false;
        boolean successWithMessageCalled = false;
        boolean failureCalled = false;
        Object lastPayload = null;

        public void step1(Object payload) {
            step1Called = true;
            lastPayload = payload;
        }

        public void step2(Object payload) {
            step2Called = true;
        }

        public void step3(Object payload) {
            step3Called = true;
        }

        public void failingStep(Object payload) {
            throw new RuntimeException("Step failed intentionally");
        }

        public void illegalStep(Object payload) {
            throw new IllegalArgumentException("Invalid argument");
        }

        public void deepStackStep(Object payload) {
            // Generate a deep stack trace by recursive calls
            deepRecursive(50);
        }

        private void deepRecursive(int depth) {
            if (depth <= 0) {
                throw new RuntimeException("Deep exception with very long message: "
                        + "A".repeat(500));
            }
            deepRecursive(depth - 1);
        }

        public void onSuccess(Object payload) {
            successCalled = true;
        }

        public void onSuccessNoParams() {
            successNoParamsCalled = true;
        }

        public void onSuccessWithMessage(WorkflowMessage message) {
            successWithMessageCalled = true;
        }

        public void onFailure(Object payload, Throwable error) {
            failureCalled = true;
        }
    }
}
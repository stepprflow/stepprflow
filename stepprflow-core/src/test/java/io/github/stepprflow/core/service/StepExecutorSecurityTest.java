package io.github.stepprflow.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stepprflow.core.StepprFlowProperties;
import io.github.stepprflow.core.broker.MessageBroker;
import io.github.stepprflow.core.model.StepDefinition;
import io.github.stepprflow.core.model.WorkflowDefinition;
import io.github.stepprflow.core.model.WorkflowMessage;
import io.github.stepprflow.core.model.WorkflowStatus;
import io.github.stepprflow.core.security.SecurityContextPropagator;
import io.github.stepprflow.core.security.SecurityContextSigner;
import io.github.stepprflow.core.security.TrustedClassResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StepExecutor Security Propagation Tests")
class StepExecutorSecurityTest {

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

    private StepExecutor stepExecutor;

    private WorkflowMessage testMessage;
    private TestWorkflow testWorkflow;

    @BeforeEach
    void setUp() {
        // Real signer with no secret -> signing disabled -> pass-through, so
        // restore() receives the raw context as before.
        SecurityContextSigner signer =
                new SecurityContextSigner(new StepprFlowProperties());
        stepExecutor = new StepExecutor(
                registry,
                messageBroker,
                properties,
                objectMapper,
                backoffCalculator,
                callbackMethodInvoker,
                securityContextPropagator,
                trustedClassResolver,
                signer
        );

        testWorkflow = new TestWorkflow();

        testMessage = WorkflowMessage.builder()
                .executionId("exec-123")
                .correlationId("corr-456")
                .topic("test-topic")
                .currentStep(1)
                .totalSteps(2)
                .status(WorkflowStatus.IN_PROGRESS)
                .payload(Map.of("key", "value"))
                .securityContext("jwt-token-123")
                .createdAt(Instant.now())
                .build();
    }

    @Nested
    @DisplayName("Security context propagation")
    class SecurityContextPropagationTests {

        @Test
        @DisplayName("Should restore security context before step execution")
        void shouldRestoreSecurityContextBeforeStepExecution() throws Exception {
            StepDefinition step1 = createStepDefinition(1, "step1");
            StepDefinition step2 = createStepDefinition(2, "step2");
            WorkflowDefinition definition = createWorkflowDefinition(List.of(step1, step2));

            when(registry.getDefinition("test-topic")).thenReturn(definition);

            stepExecutor.execute(testMessage);

            verify(securityContextPropagator).restore("jwt-token-123");
        }

        @Test
        @DisplayName("Should clear security context after successful step execution")
        void shouldClearSecurityContextAfterSuccessfulStepExecution() throws Exception {
            StepDefinition step1 = createStepDefinition(1, "step1");
            StepDefinition step2 = createStepDefinition(2, "step2");
            WorkflowDefinition definition = createWorkflowDefinition(List.of(step1, step2));

            when(registry.getDefinition("test-topic")).thenReturn(definition);

            stepExecutor.execute(testMessage);

            verify(securityContextPropagator).clear();
        }

        @Test
        @DisplayName("Should clear security context after step execution failure")
        void shouldClearSecurityContextAfterStepExecutionFailure() throws Exception {
            StepDefinition step = createFailingStepDefinition(1, "failingStep");
            WorkflowDefinition definition = createWorkflowDefinition(List.of(step));

            when(registry.getDefinition("test-topic")).thenReturn(definition);
            when(properties.getRetry()).thenReturn(createRetryConfig());

            stepExecutor.execute(testMessage);

            verify(securityContextPropagator).clear();
        }

        @Test
        @DisplayName("Should restore then clear in correct order")
        void shouldRestoreThenClearInCorrectOrder() throws Exception {
            StepDefinition step1 = createStepDefinition(1, "step1");
            StepDefinition step2 = createStepDefinition(2, "step2");
            WorkflowDefinition definition = createWorkflowDefinition(List.of(step1, step2));

            when(registry.getDefinition("test-topic")).thenReturn(definition);

            stepExecutor.execute(testMessage);

            InOrder inOrder = inOrder(securityContextPropagator);
            inOrder.verify(securityContextPropagator).restore("jwt-token-123");
            inOrder.verify(securityContextPropagator).clear();
        }

        @Test
        @DisplayName("Should not restore when security context is null")
        void shouldNotRestoreWhenSecurityContextIsNull() throws Exception {
            testMessage = testMessage.toBuilder()
                    .securityContext(null)
                    .build();

            StepDefinition step1 = createStepDefinition(1, "step1");
            StepDefinition step2 = createStepDefinition(2, "step2");
            WorkflowDefinition definition = createWorkflowDefinition(List.of(step1, step2));

            when(registry.getDefinition("test-topic")).thenReturn(definition);

            stepExecutor.execute(testMessage);

            verify(securityContextPropagator, never()).restore(any());
            verify(securityContextPropagator).clear();
        }

        @Test
        @DisplayName("Should not call propagator when definition not found")
        void shouldNotCallPropagatorWhenDefinitionNotFound() {
            when(registry.getDefinition("test-topic")).thenReturn(null);

            stepExecutor.execute(testMessage);

            verify(securityContextPropagator, never()).restore(any());
            verify(securityContextPropagator, never()).clear();
        }

        @Test
        @DisplayName("Should not call propagator when step not found")
        void shouldNotCallPropagatorWhenStepNotFound() throws Exception {
            StepDefinition step99 = createStepDefinition(99, "step1");
            WorkflowDefinition definition = createWorkflowDefinition(List.of(step99));

            when(registry.getDefinition("test-topic")).thenReturn(definition);

            stepExecutor.execute(testMessage);

            verify(securityContextPropagator, never()).restore(any());
            verify(securityContextPropagator, never()).clear();
        }

        @Test
        @DisplayName("Should have security context during step method invocation")
        void shouldHaveSecurityContextDuringStepMethodInvocation() throws Exception {
            // Use a special workflow that captures whether security was set
            SecurityAwareWorkflow securityAwareWorkflow = new SecurityAwareWorkflow(securityContextPropagator);

            StepDefinition step = StepDefinition.builder()
                    .id(1)
                    .label("securityStep")
                    .method(SecurityAwareWorkflow.class.getDeclaredMethod("securityStep", Object.class))
                    .build();

            WorkflowDefinition definition = WorkflowDefinition.builder()
                    .topic("test-topic")
                    .handler(securityAwareWorkflow)
                    .handlerClass(SecurityAwareWorkflow.class)
                    .steps(List.of(step))
                    .partitions(1)
                    .replication((short) 1)
                    .build();

            when(registry.getDefinition("test-topic")).thenReturn(definition);

            stepExecutor.execute(testMessage);

            assertThat(securityAwareWorkflow.securityWasRestored).isTrue();
        }
    }

    // Helper methods
    private StepDefinition createStepDefinition(int id, String methodName) throws Exception {
        Method method = TestWorkflow.class.getDeclaredMethod(methodName, Object.class);
        return StepDefinition.builder()
                .id(id)
                .label(methodName)
                .method(method)
                .build();
    }

    private StepDefinition createFailingStepDefinition(int id, String methodName) throws Exception {
        Method method = TestWorkflow.class.getDeclaredMethod(methodName, Object.class);
        return StepDefinition.builder()
                .id(id)
                .label(methodName)
                .method(method)
                .build();
    }

    private WorkflowDefinition createWorkflowDefinition(List<StepDefinition> steps) {
        return WorkflowDefinition.builder()
                .topic("test-topic")
                .handler(testWorkflow)
                .handlerClass(TestWorkflow.class)
                .steps(steps)
                .partitions(1)
                .replication((short) 1)
                .build();
    }

    private StepprFlowProperties.Retry createRetryConfig() {
        StepprFlowProperties.Retry retry = new StepprFlowProperties.Retry();
        retry.setMaxAttempts(3);
        retry.setNonRetryableExceptions(List.of());
        return retry;
    }

    // Test workflow
    static class TestWorkflow implements StepprFlow {
        boolean step1Called = false;

        public void step1(Object payload) {
            step1Called = true;
        }

        public void step2(Object payload) {
        }

        public void failingStep(Object payload) {
            throw new RuntimeException("Failed");
        }
    }

    // Workflow that verifies security context during execution
    static class SecurityAwareWorkflow implements StepprFlow {
        private final SecurityContextPropagator propagator;
        boolean securityWasRestored = false;

        SecurityAwareWorkflow(SecurityContextPropagator propagator) {
            this.propagator = propagator;
        }

        public void securityStep(Object payload) {
            // Verify that restore was called by checking the mock
            try {
                verify(propagator).restore(any());
                securityWasRestored = true;
            } catch (Exception e) {
                securityWasRestored = false;
            }
        }
    }
}
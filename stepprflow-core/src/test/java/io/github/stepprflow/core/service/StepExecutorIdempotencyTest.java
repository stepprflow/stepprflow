package io.github.stepprflow.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stepprflow.core.StepprFlowProperties;
import io.github.stepprflow.core.broker.MessageBroker;
import io.github.stepprflow.core.idempotency.IdempotencyKey;
import io.github.stepprflow.core.idempotency.IdempotencyStore;
import io.github.stepprflow.core.model.StepDefinition;
import io.github.stepprflow.core.model.WorkflowDefinition;
import io.github.stepprflow.core.model.WorkflowMessage;
import io.github.stepprflow.core.model.WorkflowStatus;
import io.github.stepprflow.core.security.SecurityContextPropagator;
import io.github.stepprflow.core.security.SecurityContextSigner;
import io.github.stepprflow.core.security.TrustedClassResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SF-8: verifies that step de-duplication is wired into the executor — a step
 * already recorded as processed is skipped (not run, not re-sent), a fresh step
 * runs and is recorded after success, a failing step is not recorded, and with
 * the feature off the store is never consulted.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepExecutor idempotency (SF-8)")
class StepExecutorIdempotencyTest {

    @Mock
    private WorkflowRegistry registry;
    @Mock
    private MessageBroker messageBroker;
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
    private IdempotencyStore idempotencyStore;

    private final RecordingWorkflow workflow = new RecordingWorkflow();
    private WorkflowMessage message;

    private StepExecutor newExecutor(final boolean idempotencyEnabled) {
        StepprFlowProperties props = new StepprFlowProperties();
        props.getIdempotency().setEnabled(idempotencyEnabled);
        SecurityContextSigner signer = new SecurityContextSigner(new StepprFlowProperties());
        return new StepExecutor(registry, messageBroker, props, objectMapper,
                backoffCalculator, callbackMethodInvoker, securityContextPropagator,
                trustedClassResolver, signer, idempotencyStore);
    }

    @BeforeEach
    void setUp() {
        message = WorkflowMessage.builder()
                .executionId("exec-1")
                .correlationId("corr-1")
                .topic("test-topic")
                .currentStep(1)
                .totalSteps(2)
                .status(WorkflowStatus.IN_PROGRESS)
                .payload(Map.of("k", "v"))
                .createdAt(Instant.now())
                .build();
    }

    private WorkflowDefinition twoStepDefinition() throws Exception {
        Method m = RecordingWorkflow.class.getDeclaredMethod("step", Object.class);
        return WorkflowDefinition.builder()
                .topic("test-topic")
                .handler(workflow)
                .handlerClass(RecordingWorkflow.class)
                .steps(List.of(
                        StepDefinition.builder().id(1).label("step1").method(m).build(),
                        StepDefinition.builder().id(2).label("step2").method(m).build()))
                .partitions(1)
                .replication((short) 1)
                .build();
    }

    @Test
    @DisplayName("an already-processed step is skipped, not run and not re-sent")
    void duplicateIsSkipped() throws Exception {
        when(registry.getDefinition("test-topic")).thenReturn(twoStepDefinition());
        when(idempotencyStore.isProcessed(eq(new IdempotencyKey("exec-1", 1)))).thenReturn(true);

        newExecutor(true).execute(message);

        assertThat(workflow.runCount).isZero();
        verify(messageBroker, never()).send(any(), any());
        verify(messageBroker, never()).sendSync(any(), any());
        verify(idempotencyStore, never()).markProcessed(any());
    }

    @Test
    @DisplayName("a fresh step runs, advances, then is recorded as processed")
    void freshStepRunsAndIsRecorded() throws Exception {
        when(registry.getDefinition("test-topic")).thenReturn(twoStepDefinition());
        when(idempotencyStore.isProcessed(any())).thenReturn(false);

        newExecutor(true).execute(message);

        assertThat(workflow.runCount).isEqualTo(1);
        verify(messageBroker).sendSync(eq("test-topic"), any());
        verify(idempotencyStore).markProcessed(eq(new IdempotencyKey("exec-1", 1)));
    }

    @Test
    @DisplayName("a failing step is not recorded as processed")
    void failingStepIsNotRecorded() throws Exception {
        Method m = RecordingWorkflow.class.getDeclaredMethod("failingStep", Object.class);
        WorkflowDefinition def = WorkflowDefinition.builder()
                .topic("test-topic").handler(workflow).handlerClass(RecordingWorkflow.class)
                .steps(List.of(StepDefinition.builder().id(1).label("f").method(m).build()))
                .partitions(1).replication((short) 1).build();
        when(registry.getDefinition("test-topic")).thenReturn(def);
        when(idempotencyStore.isProcessed(any())).thenReturn(false);
        lenient().when(backoffCalculator.calculate(anyInt())).thenReturn(java.time.Duration.ofSeconds(1));

        newExecutor(true).execute(message);

        verify(idempotencyStore, never()).markProcessed(any());
    }

    @Test
    @DisplayName("with idempotency disabled the store is never consulted")
    void disabledNeverConsultsStore() throws Exception {
        when(registry.getDefinition("test-topic")).thenReturn(twoStepDefinition());

        newExecutor(false).execute(message);

        assertThat(workflow.runCount).isEqualTo(1);
        verify(idempotencyStore, never()).isProcessed(any());
        verify(idempotencyStore, never()).markProcessed(any());
    }

    /** Workflow that counts executions. */
    static class RecordingWorkflow implements StepprFlow {
        int runCount = 0;

        public void step(Object payload) {
            runCount++;
        }

        public void failingStep(Object payload) {
            throw new RuntimeException("boom");
        }
    }
}

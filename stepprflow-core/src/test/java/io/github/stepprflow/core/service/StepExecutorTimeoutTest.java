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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

/**
 * SF-6: verifies that {@code @Timeout} is actually enforced when
 * {@code stepprflow.timeout.enabled=true} — a step past its deadline is
 * aborted (interrupted) and never completes — and that enforcement is fully
 * off (steps run inline on the consumer thread) when the flag is false.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepExecutor timeout enforcement (SF-6)")
class StepExecutorTimeoutTest {

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

    private WorkflowMessage message;

    private StepExecutor newExecutor(final boolean timeoutEnabled) {
        StepprFlowProperties props = new StepprFlowProperties();
        props.getTimeout().setEnabled(timeoutEnabled);
        // No default: only steps with an explicit @Timeout are enforced here.
        props.getTimeout().setDefaultStepTimeout(null);
        SecurityContextSigner signer = new SecurityContextSigner(new StepprFlowProperties());
        return new StepExecutor(registry, messageBroker, props, objectMapper,
                backoffCalculator, callbackMethodInvoker, securityContextPropagator,
                trustedClassResolver, signer);
    }

    @BeforeEach
    void setUp() {
        // payloadType null -> deserializePayload returns the raw payload, no objectMapper call.
        message = WorkflowMessage.builder()
                .executionId("exec-1")
                .correlationId("corr-1")
                .topic("test-topic")
                .currentStep(1)
                .totalSteps(2)
                .status(WorkflowStatus.IN_PROGRESS)
                .payload(Map.of("k", "v"))
                .securityContext("jwt-token")
                .createdAt(Instant.now())
                .build();
    }

    private WorkflowDefinition definitionWith(final StepDefinition... steps) {
        return WorkflowDefinition.builder()
                .topic("test-topic")
                .handler(workflow)
                .handlerClass(SleepWorkflow.class)
                .steps(List.of(steps))
                .partitions(1)
                .replication((short) 1)
                .build();
    }

    private final SleepWorkflow workflow = new SleepWorkflow();

    private StepDefinition slowStep(final Duration timeout) throws Exception {
        Method method = SleepWorkflow.class.getDeclaredMethod("slowStep", Object.class);
        return StepDefinition.builder().id(1).label("slowStep").method(method).timeout(timeout).build();
    }

    private StepDefinition fastStep(final Duration timeout) throws Exception {
        Method method = SleepWorkflow.class.getDeclaredMethod("fastStep", Object.class);
        return StepDefinition.builder().id(1).label("fastStep").method(method).timeout(timeout).build();
    }

    @Test
    @DisplayName("a step past its timeout is aborted (interrupted) and never completes")
    void slowStepIsAborted() throws Exception {
        when(registry.getDefinition("test-topic")).thenReturn(definitionWith(slowStep(Duration.ofMillis(150))));
        // StepTimeoutException is retryable -> handleFailure schedules a retry.
        lenient().when(backoffCalculator.calculate(anyInt())).thenReturn(Duration.ofSeconds(1));

        StepExecutor executor = newExecutor(true);
        executor.execute(message);

        // The worker must have been interrupted (cooperative cancellation worked)
        // and the step must NOT have run to completion.
        assertThat(workflow.doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(workflow.interrupted).isTrue();
        assertThat(workflow.completedNormally).isFalse();
        // The step ran on a dedicated timeout worker, not the caller thread.
        assertThat(workflow.threadName.get()).startsWith("stepprflow-timeout-");
        // Timed-out step is retried, not completed.
        verify(messageBroker, never()).sendSync(eq("test-topic.completed"), any());
        // Context restored and cleared on the worker (SF-1 hardening preserved there);
        // clear() also runs on the consumer thread, hence atLeastOnce.
        verify(securityContextPropagator, atLeastOnce()).clear();
    }

    @Test
    @DisplayName("a fast step under enforcement completes and advances")
    void fastStepAdvances() throws Exception {
        StepDefinition step1 = fastStep(Duration.ofSeconds(5));
        Method m2 = SleepWorkflow.class.getDeclaredMethod("fastStep", Object.class);
        StepDefinition step2 = StepDefinition.builder().id(2).label("step2").method(m2).build();
        when(registry.getDefinition("test-topic")).thenReturn(definitionWith(step1, step2));

        StepExecutor executor = newExecutor(true);
        executor.execute(message);

        assertThat(workflow.completedNormally).isTrue();
        assertThat(workflow.threadName.get()).startsWith("stepprflow-timeout-");
        // Advanced to the next step (not the last step -> no .completed yet).
        verify(messageBroker).sendSync(eq("test-topic"), any());
    }

    @Test
    @DisplayName("with enforcement OFF a step runs inline on the caller thread, even with @Timeout")
    void disabledRunsInline() throws Exception {
        StepDefinition step1 = fastStep(Duration.ofMillis(1));
        Method m2 = SleepWorkflow.class.getDeclaredMethod("fastStep", Object.class);
        StepDefinition step2 = StepDefinition.builder().id(2).label("step2").method(m2).build();
        when(registry.getDefinition("test-topic")).thenReturn(definitionWith(step1, step2));

        String callerThread = Thread.currentThread().getName();
        StepExecutor executor = newExecutor(false);
        executor.execute(message);

        assertThat(workflow.completedNormally).isTrue();
        // Ran inline on the caller thread, not a timeout worker -> non-breaking default.
        assertThat(workflow.threadName.get()).isEqualTo(callerThread);
    }

    /** Workflow whose steps record the thread they ran on and interruption. */
    static class SleepWorkflow implements StepprFlow {
        final CountDownLatch doneLatch = new CountDownLatch(1);
        final AtomicReference<String> threadName = new AtomicReference<>();
        volatile boolean interrupted = false;
        volatile boolean completedNormally = false;

        public void slowStep(Object payload) {
            threadName.set(Thread.currentThread().getName());
            try {
                Thread.sleep(2000);
                completedNormally = true;
            } catch (InterruptedException e) {
                interrupted = true;
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        }

        public void fastStep(Object payload) {
            threadName.set(Thread.currentThread().getName());
            completedNormally = true;
            doneLatch.countDown();
        }
    }
}

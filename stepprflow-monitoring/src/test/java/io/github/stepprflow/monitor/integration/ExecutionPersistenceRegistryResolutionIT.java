package io.github.stepprflow.monitor.integration;

import io.github.stepprflow.core.model.WorkflowMessage;
import io.github.stepprflow.core.model.WorkflowStatus;
import io.github.stepprflow.monitor.model.RegisteredWorkflow;
import io.github.stepprflow.monitor.model.WorkflowExecution;
import io.github.stepprflow.monitor.repository.RegisteredWorkflowRepository;
import io.github.stepprflow.monitor.repository.WorkflowExecutionRepository;
import io.github.stepprflow.monitor.service.ExecutionPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the production incident where a topic registered by more than
 * one service (unique index is the compound key {@code (topic, serviceName)},
 * not {@code topic} alone) made {@code resolveStepsFromRegistry} throw
 * {@code IncorrectResultSizeDataAccessException}, aborting
 * {@code onWorkflowMessage} entirely: the execution was never persisted and
 * stayed IN_PROGRESS forever from the dashboard's point of view.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@Testcontainers
@WithMockUser(authorities = {"OPERATOR"})
@DisplayName("ExecutionPersistenceService: totalSteps resolution for a multi-service topic")
class ExecutionPersistenceRegistryResolutionIT extends MongoDBTestContainerConfig {

    @Autowired
    private ExecutionPersistenceService persistenceService;

    @Autowired
    private RegisteredWorkflowRepository registeredWorkflowRepository;

    @Autowired
    private WorkflowExecutionRepository workflowExecutionRepository;

    @BeforeEach
    void setUp() {
        registeredWorkflowRepository.deleteAll();
        workflowExecutionRepository.deleteAll();
    }

    @Test
    @DisplayName("resolves totalSteps instead of aborting when the topic is registered by two services")
    void resolvesStepsWhenTopicRegisteredByMultipleServices() {
        List<RegisteredWorkflow.StepInfo> steps = List.of(
                RegisteredWorkflow.StepInfo.builder().id(1).label("Step 1").build(),
                RegisteredWorkflow.StepInfo.builder().id(2).label("Step 2").build(),
                RegisteredWorkflow.StepInfo.builder().id(3).label("Step 3").build());

        registeredWorkflowRepository.save(RegisteredWorkflow.builder()
                .topic("references-events")
                .serviceName("cockpit-svc-references")
                .steps(steps)
                .registeredBy(new HashSet<>())
                .status(RegisteredWorkflow.Status.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        registeredWorkflowRepository.save(RegisteredWorkflow.builder()
                .topic("references-events")
                .serviceName("cockpit-svc-sales")
                .steps(steps)
                .registeredBy(new HashSet<>())
                .status(RegisteredWorkflow.Status.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        WorkflowMessage message = WorkflowMessage.builder()
                .executionId("exec-multi-service-topic")
                .topic("references-events")
                .currentStep(1)
                .totalSteps(0) // cross-service start: not known by the caller
                .status(WorkflowStatus.IN_PROGRESS)
                .createdAt(Instant.now())
                .build();

        persistenceService.onWorkflowMessage(message);

        WorkflowExecution saved = workflowExecutionRepository.findById("exec-multi-service-topic")
                .orElseThrow(() -> new AssertionError("execution was not persisted"));
        assertThat(saved.getTotalSteps()).isEqualTo(3);
    }

    @Test
    @DisplayName("resolves totalSteps via the message's serviceName when two services "
            + "register the same topic with a DIFFERENT step count")
    void resolvesStepsUsingServiceNameWhenStepCountsDiffer() {
        registeredWorkflowRepository.save(RegisteredWorkflow.builder()
                .topic("references-events")
                .serviceName("cockpit-svc-sales")
                .steps(List.of(
                        RegisteredWorkflow.StepInfo.builder().id(1).label("Step 1").build(),
                        RegisteredWorkflow.StepInfo.builder().id(2).label("Step 2").build(),
                        RegisteredWorkflow.StepInfo.builder().id(3).label("Step 3").build(),
                        RegisteredWorkflow.StepInfo.builder().id(4).label("Step 4").build(),
                        RegisteredWorkflow.StepInfo.builder().id(5).label("Step 5").build()))
                .registeredBy(new HashSet<>())
                .status(RegisteredWorkflow.Status.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        registeredWorkflowRepository.save(RegisteredWorkflow.builder()
                .topic("references-events")
                .serviceName("cockpit-svc-references")
                .steps(List.of(
                        RegisteredWorkflow.StepInfo.builder().id(1).label("Step 1").build(),
                        RegisteredWorkflow.StepInfo.builder().id(2).label("Step 2").build()))
                .registeredBy(new HashSet<>())
                .status(RegisteredWorkflow.Status.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        WorkflowMessage message = WorkflowMessage.builder()
                .executionId("exec-service-disambiguated")
                .topic("references-events")
                .serviceName("cockpit-svc-sales")
                .currentStep(1)
                .totalSteps(0)
                .status(WorkflowStatus.IN_PROGRESS)
                .createdAt(Instant.now())
                .build();

        persistenceService.onWorkflowMessage(message);

        WorkflowExecution saved = workflowExecutionRepository.findById("exec-service-disambiguated")
                .orElseThrow(() -> new AssertionError("execution was not persisted"));
        assertThat(saved.getTotalSteps()).isEqualTo(5);
    }
}

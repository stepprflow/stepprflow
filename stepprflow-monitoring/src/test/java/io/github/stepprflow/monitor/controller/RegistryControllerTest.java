package io.github.stepprflow.monitor.controller;

import io.github.stepprflow.core.model.WorkflowRegistrationRequest;
import io.github.stepprflow.monitor.model.RegisteredWorkflow;
import io.github.stepprflow.monitor.service.WorkflowRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for RegistryController.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RegistryController Tests")
class RegistryControllerTest {

    @Mock
    private WorkflowRegistryService registryService;

    private RegistryController controller;

    @BeforeEach
    void setUp() {
        controller = new RegistryController(registryService);
    }

    @Nested
    @DisplayName("POST /workflows - registerWorkflows()")
    class RegisterWorkflowsTests {

        @Test
        @DisplayName("should register workflows and return OK")
        void shouldRegisterWorkflowsAndReturnOk() {
            // Given
            WorkflowRegistrationRequest request = WorkflowRegistrationRequest.builder()
                    .serviceName("order-service")
                    .instanceId("instance-1")
                    .host("localhost")
                    .port(8080)
                    .workflows(List.of())
                    .build();

            // When
            ResponseEntity<Void> response = controller.registerWorkflows(request);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(registryService).registerWorkflows(request);
        }

        @Test
        @DisplayName("should pass request with workflows to registry service")
        void shouldPassRequestWithWorkflowsToRegistryService() {
            // Given
            List<WorkflowRegistrationRequest.WorkflowInfo> workflows = new ArrayList<>();
            workflows.add(WorkflowRegistrationRequest.WorkflowInfo.builder()
                    .topic("order-workflow")
                    .description("Order processing workflow")
                    .build());

            WorkflowRegistrationRequest request = WorkflowRegistrationRequest.builder()
                    .serviceName("order-service")
                    .instanceId("instance-1")
                    .host("localhost")
                    .port(8080)
                    .workflows(workflows)
                    .build();

            // When
            ResponseEntity<Void> response = controller.registerWorkflows(request);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(registryService).registerWorkflows(request);
        }
    }

    @Nested
    @DisplayName("GET /workflows - getAllWorkflows()")
    class GetAllWorkflowsTests {

        @Test
        @DisplayName("should return all workflows from registry service")
        void shouldReturnAllWorkflowsFromRegistryService() {
            // Given
            List<RegisteredWorkflow> workflows = List.of(
                    RegisteredWorkflow.builder()
                            .topic("order-workflow")
                            .serviceName("order-service")
                            .build(),
                    RegisteredWorkflow.builder()
                            .topic("payment-workflow")
                            .serviceName("payment-service")
                            .build()
            );
            when(registryService.getAllWorkflows()).thenReturn(workflows);

            // When
            ResponseEntity<List<RegisteredWorkflow>> response = controller.getAllWorkflows();

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(2);
            assertThat(response.getBody()).extracting(RegisteredWorkflow::getTopic)
                    .containsExactly("order-workflow", "payment-workflow");
        }

        @Test
        @DisplayName("should return empty list when no workflows registered")
        void shouldReturnEmptyListWhenNoWorkflowsRegistered() {
            // Given
            when(registryService.getAllWorkflows()).thenReturn(List.of());

            // When
            ResponseEntity<List<RegisteredWorkflow>> response = controller.getAllWorkflows();

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEmpty();
        }
    }

    @Nested
    @DisplayName("GET /workflows/{topic} - getWorkflow()")
    class GetWorkflowTests {

        @Test
        @DisplayName("should return workflow when found")
        void shouldReturnWorkflowWhenFound() {
            // Given
            RegisteredWorkflow workflow = RegisteredWorkflow.builder()
                    .topic("order-workflow")
                    .serviceName("order-service")
                    .description("Order processing workflow")
                    .build();
            when(registryService.getWorkflow("order-workflow")).thenReturn(workflow);

            // When
            ResponseEntity<RegisteredWorkflow> response = controller.getWorkflow("order-workflow");

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getTopic()).isEqualTo("order-workflow");
            assertThat(response.getBody().getServiceName()).isEqualTo("order-service");
        }

        @Test
        @DisplayName("should return 404 when workflow not found")
        void shouldReturn404WhenWorkflowNotFound() {
            // Given
            when(registryService.getWorkflow("non-existent-workflow")).thenReturn(null);

            // When
            ResponseEntity<RegisteredWorkflow> response = controller.getWorkflow("non-existent-workflow");

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNull();
        }
    }

    @Nested
    @DisplayName("DELETE /services/{serviceName}/instances/{instanceId} - unregisterService()")
    class UnregisterServiceTests {

        @Test
        @DisplayName("should unregister service and return OK")
        void shouldUnregisterServiceAndReturnOk() {
            // When
            ResponseEntity<Void> response = controller.unregisterService("order-service", "instance-1");

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(registryService).unregisterService("order-service", "instance-1");
        }

        @Test
        @DisplayName("should call registry service with correct parameters")
        void shouldCallRegistryServiceWithCorrectParameters() {
            // When
            controller.unregisterService("payment-service", "instance-xyz");

            // Then
            verify(registryService).unregisterService("payment-service", "instance-xyz");
        }
    }

    @Nested
    @DisplayName("POST /services/{serviceName}/instances/{instanceId}/heartbeat - heartbeat()")
    class HeartbeatTests {

        @Test
        @DisplayName("should process heartbeat and return OK")
        void shouldProcessHeartbeatAndReturnOk() {
            // When
            ResponseEntity<Void> response = controller.heartbeat("order-service", "instance-1");

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(registryService).heartbeat("order-service", "instance-1");
        }

        @Test
        @DisplayName("should call registry service heartbeat with correct parameters")
        void shouldCallRegistryServiceHeartbeatWithCorrectParameters() {
            // When
            controller.heartbeat("notification-service", "instance-abc");

            // Then
            verify(registryService).heartbeat("notification-service", "instance-abc");
        }
    }

    @Nested
    @DisplayName("DELETE /workflows/{workflowId} - purgeWorkflow()")
    class PurgeWorkflowTests {

        @Test
        @DisplayName("should return 204 and call service")
        void shouldReturn204AndCallService() {
            // When
            ResponseEntity<Void> response = controller.purgeWorkflow("wf-1");

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(registryService).purgeWorkflow("wf-1");
        }
    }

    @Nested
    @DisplayName("DELETE /workflows/inactive - purgeAllInactiveWorkflows()")
    class PurgeAllInactiveWorkflowsTests {

        @Test
        @DisplayName("should return 200 with purged count")
        void shouldReturn200WithPurgedCount() {
            // Given
            when(registryService.purgeAllInactiveWorkflows()).thenReturn(5L);

            // When
            ResponseEntity<Map<String, Long>> response = controller.purgeAllInactiveWorkflows();

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("purgedCount", 5L);
        }

        @Test
        @DisplayName("should return 200 with zero count when none inactive")
        void shouldReturn200WithZeroCount() {
            // Given
            when(registryService.purgeAllInactiveWorkflows()).thenReturn(0L);

            // When
            ResponseEntity<Map<String, Long>> response = controller.purgeAllInactiveWorkflows();

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("purgedCount", 0L);
        }
    }
}
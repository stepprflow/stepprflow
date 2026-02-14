package io.github.stepprflow.monitor.controller;

import io.github.stepprflow.core.model.WorkflowRegistrationRequest;
import io.github.stepprflow.monitor.model.RegisteredWorkflow;
import io.github.stepprflow.monitor.service.WorkflowRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST API for workflow registration from microservices.
 */
@RestController
@RequestMapping("/api/registry")
@RequiredArgsConstructor
@Tag(name = "Registry", description = "Workflow registration from microservices")
public class RegistryController {

    private final WorkflowRegistryService registryService;

    @Operation(summary = "Register workflows", description = "Register workflow definitions from a microservice")
    @ApiResponse(responseCode = "200", description = "Workflows registered successfully")
    @PostMapping("/workflows")
    public ResponseEntity<Void> registerWorkflows(@RequestBody WorkflowRegistrationRequest request) {
        registryService.registerWorkflows(request);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Get all registered workflows",
            description = "Get all workflow definitions registered by microservices")
    @ApiResponse(responseCode = "200", description = "List of registered workflows")
    @GetMapping("/workflows")
    public ResponseEntity<List<RegisteredWorkflow>> getAllWorkflows() {
        return ResponseEntity.ok(registryService.getAllWorkflows());
    }

    @Operation(summary = "Get workflow by topic", description = "Get a specific workflow definition by topic")
    @ApiResponse(responseCode = "200", description = "Workflow found")
    @ApiResponse(responseCode = "404", description = "Workflow not found")
    @GetMapping("/workflows/{topic}")
    public ResponseEntity<RegisteredWorkflow> getWorkflow(@PathVariable String topic) {
        RegisteredWorkflow workflow = registryService.getWorkflow(topic);
        if (workflow == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(workflow);
    }

    @Operation(summary = "Purge all inactive workflows",
            description = "Delete all workflows with INACTIVE status from the registry")
    @ApiResponse(responseCode = "200", description = "Inactive workflows purged")
    @DeleteMapping("/workflows/inactive")
    public ResponseEntity<Map<String, Long>> purgeAllInactiveWorkflows() {
        long count = registryService.purgeAllInactiveWorkflows();
        return ResponseEntity.ok(Map.of("purgedCount", count));
    }

    @Operation(summary = "Purge a single workflow",
            description = "Delete a single INACTIVE workflow by its MongoDB ID")
    @ApiResponse(responseCode = "204", description = "Workflow purged")
    @ApiResponse(responseCode = "404", description = "Workflow not found")
    @ApiResponse(responseCode = "409", description = "Workflow is still ACTIVE")
    @DeleteMapping("/workflows/{workflowId}")
    public ResponseEntity<Void> purgeWorkflow(@PathVariable String workflowId) {
        registryService.purgeWorkflow(workflowId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Unregister service", description = "Unregister a service instance (called on shutdown)")
    @ApiResponse(responseCode = "200", description = "Service unregistered")
    @DeleteMapping("/services/{serviceName}/instances/{instanceId}")
    public ResponseEntity<Void> unregisterService(
            @PathVariable String serviceName,
            @PathVariable String instanceId) {
        registryService.unregisterService(serviceName, instanceId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Service heartbeat", description = "Update heartbeat for a service instance")
    @ApiResponse(responseCode = "200", description = "Heartbeat received")
    @PostMapping("/services/{serviceName}/instances/{instanceId}/heartbeat")
    public ResponseEntity<Void> heartbeat(
            @PathVariable String serviceName,
            @PathVariable String instanceId) {
        registryService.heartbeat(serviceName, instanceId);
        return ResponseEntity.ok().build();
    }
}

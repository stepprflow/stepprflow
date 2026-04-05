package io.github.stepprflow.monitor.service;

import io.github.stepprflow.core.model.WorkflowRegistrationRequest;
import io.github.stepprflow.monitor.MonitorProperties;
import io.github.stepprflow.monitor.model.RegisteredWorkflow;
import io.github.stepprflow.monitor.repository.RegisteredWorkflowRepository;
import io.github.stepprflow.monitor.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for managing workflow registrations from microservices.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowRegistryService {

    private final RegisteredWorkflowRepository repository;
    private final MonitorProperties properties;

    /**
     * Register workflows from a service.
     */
    public void registerWorkflows(WorkflowRegistrationRequest request) {
        log.info("Registering {} workflows from service {} (instance: {})",
                request.getWorkflows().size(),
                request.getServiceName(),
                request.getInstanceId());

        RegisteredWorkflow.ServiceInstance serviceInstance = RegisteredWorkflow.ServiceInstance.builder()
                .serviceName(request.getServiceName())
                .instanceId(request.getInstanceId())
                .host(request.getHost())
                .port(request.getPort())
                .lastHeartbeat(Instant.now())
                .build();

        for (WorkflowRegistrationRequest.WorkflowInfo workflowInfo : request.getWorkflows()) {
            registerOrUpdateWorkflow(workflowInfo, serviceInstance);
        }
    }

    private void registerOrUpdateWorkflow(
            WorkflowRegistrationRequest.WorkflowInfo workflowInfo,
            RegisteredWorkflow.ServiceInstance serviceInstance) {

        // Use composite key: topic + serviceName
        RegisteredWorkflow existing = repository.findByTopicAndServiceName(
                workflowInfo.getTopic(),
                serviceInstance.getServiceName()
        ).orElse(null);

        if (existing != null) {
            // Update existing workflow
            updateWorkflow(existing, workflowInfo, serviceInstance);
        } else {
            // Create new workflow registration
            createWorkflow(workflowInfo, serviceInstance);
        }
    }

    private void createWorkflow(
            WorkflowRegistrationRequest.WorkflowInfo workflowInfo,
            RegisteredWorkflow.ServiceInstance serviceInstance) {

        List<RegisteredWorkflow.StepInfo> steps = workflowInfo.getSteps().stream()
                .map(s -> RegisteredWorkflow.StepInfo.builder()
                        .id(s.getId())
                        .label(s.getLabel())
                        .description(s.getDescription())
                        .skippable(s.isSkippable())
                        .continueOnFailure(s.isContinueOnFailure())
                        .timeoutMs(s.getTimeoutMs())
                        .build())
                .collect(Collectors.toList());

        HashSet<RegisteredWorkflow.ServiceInstance> instances = new HashSet<>();
        instances.add(serviceInstance);

        RegisteredWorkflow workflow = RegisteredWorkflow.builder()
                .topic(workflowInfo.getTopic())
                .serviceName(serviceInstance.getServiceName())
                .description(workflowInfo.getDescription())
                .steps(steps)
                .partitions(workflowInfo.getPartitions())
                .replication(workflowInfo.getReplication())
                .timeoutMs(workflowInfo.getTimeoutMs())
                .registeredBy(instances)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        repository.save(workflow);
        log.info("Registered new workflow: {} from service {}",
                workflowInfo.getTopic(), serviceInstance.getServiceName());
    }

    private void updateWorkflow(
            RegisteredWorkflow existing,
            WorkflowRegistrationRequest.WorkflowInfo workflowInfo,
            RegisteredWorkflow.ServiceInstance serviceInstance) {

        // Update steps if changed
        List<RegisteredWorkflow.StepInfo> steps = workflowInfo.getSteps().stream()
                .map(s -> RegisteredWorkflow.StepInfo.builder()
                        .id(s.getId())
                        .label(s.getLabel())
                        .description(s.getDescription())
                        .skippable(s.isSkippable())
                        .continueOnFailure(s.isContinueOnFailure())
                        .timeoutMs(s.getTimeoutMs())
                        .build())
                .collect(Collectors.toList());

        existing.setDescription(workflowInfo.getDescription());
        existing.setSteps(steps);
        existing.setPartitions(workflowInfo.getPartitions());
        existing.setReplication(workflowInfo.getReplication());
        existing.setTimeoutMs(workflowInfo.getTimeoutMs());
        existing.setUpdatedAt(Instant.now());

        // Remove old entry for this instance and add new one
        existing.removeServiceInstancesIf(
                si -> si.getInstanceId().equals(serviceInstance.getInstanceId()));
        existing.addServiceInstance(serviceInstance);

        // Reactivate workflow if it was inactive
        if (existing.getStatus() == RegisteredWorkflow.Status.INACTIVE) {
            existing.setStatus(RegisteredWorkflow.Status.ACTIVE);
            log.info("Workflow {} reactivated by instance {}",
                    workflowInfo.getTopic(), serviceInstance.getInstanceId());
        }

        repository.save(existing);
        log.debug("Updated workflow: {}", workflowInfo.getTopic());
    }

    /**
     * Get all registered workflows.
     */
    public List<RegisteredWorkflow> getAllWorkflows() {
        return repository.findAll();
    }

    /**
     * Get workflow by topic.
     */
    public RegisteredWorkflow getWorkflow(String topic) {
        return repository.findByTopic(topic).orElse(null);
    }

    /**
     * Unregister a service instance (called on shutdown).
     */
    public void unregisterService(String serviceName, String instanceId) {
        log.info("Unregistering service {} instance {}", serviceName, instanceId);

        List<RegisteredWorkflow> workflows = repository.findAll();
        for (RegisteredWorkflow workflow : workflows) {
            boolean removed = workflow.removeServiceInstancesIf(
                    si -> si.getServiceName().equals(serviceName) &&
                            si.getInstanceId().equals(instanceId));
            if (removed) {
                workflow.setUpdatedAt(Instant.now());
                repository.save(workflow);
            }
        }
    }

    /**
     * Heartbeat from a service instance.
     */
    public void heartbeat(String serviceName, String instanceId) {
        List<RegisteredWorkflow> workflows = repository.findAll();
        Instant now = Instant.now();

        for (RegisteredWorkflow workflow : workflows) {
            Set<RegisteredWorkflow.ServiceInstance> instances = workflow.getRegisteredByInternal();
            if (instances != null) {
                for (RegisteredWorkflow.ServiceInstance instance : instances) {
                    if (instance.getServiceName().equals(serviceName) &&
                            instance.getInstanceId().equals(instanceId)) {
                        instance.setLastHeartbeat(now);
                    }
                }
                repository.save(workflow);
            }
        }
    }

    /**
     * Purge (delete) a single workflow by its MongoDB ID.
     * Only INACTIVE workflows can be purged.
     *
     * @param workflowId the MongoDB document ID
     * @throws ResourceNotFoundException if the workflow does not exist
     * @throws IllegalStateException     if the workflow is still ACTIVE
     */
    public void purgeWorkflow(String workflowId) {
        RegisteredWorkflow workflow = repository.findById(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("RegisteredWorkflow", workflowId));

        if (workflow.getStatus() == RegisteredWorkflow.Status.ACTIVE) {
            throw new IllegalStateException(
                    "Cannot purge ACTIVE workflow " + workflowId + ". Deactivate it first.");
        }

        repository.deleteById(workflowId);
        log.info("Purged inactive workflow: {} (topic={})", workflowId, workflow.getTopic());
    }

    /**
     * Purge all INACTIVE workflows.
     *
     * @return the number of workflows deleted
     */
    public long purgeAllInactiveWorkflows() {
        long count = repository.deleteByStatus(RegisteredWorkflow.Status.INACTIVE);
        log.info("Purged {} inactive workflow(s)", count);
        return count;
    }

    /**
     * Clean up stale service instances that haven't sent a heartbeat within the timeout period.
     * This is scheduled to run periodically based on the configured cleanup interval.
     * When all instances are removed, the workflow is marked as INACTIVE.
     *
     * @return the number of stale instances removed
     */
    @Scheduled(fixedRateString = "${stepprflow.monitor.registry.cleanup-interval:PT1M}")
    public int cleanupStaleInstances() {
        Duration timeout = properties.getRegistry().getInstanceTimeout();
        Instant cutoff = Instant.now().minus(timeout);
        int totalRemoved = 0;

        List<RegisteredWorkflow> workflows = repository.findAll();

        for (RegisteredWorkflow workflow : workflows) {
            boolean needsSave = false;
            int removedCount;

            Set<RegisteredWorkflow.ServiceInstance> instances = workflow.getRegisteredByInternal();
            if (instances != null && !instances.isEmpty()) {
                int sizeBefore = instances.size();

                // Remove instances with stale or null heartbeat
                boolean removed = instances.removeIf(instance ->
                        instance.getLastHeartbeat() == null ||
                        !instance.getLastHeartbeat().isAfter(cutoff));

                if (removed) {
                    removedCount = sizeBefore - instances.size();
                    totalRemoved += removedCount;
                    needsSave = true;
                    log.info("Removed {} stale instance(s) from workflow {}", removedCount, workflow.getTopic());
                }
            }

            // Check if workflow should be marked as INACTIVE
            if (workflow.hasNoServiceInstances() && workflow.getStatus() == RegisteredWorkflow.Status.ACTIVE) {
                workflow.setStatus(RegisteredWorkflow.Status.INACTIVE);
                needsSave = true;
                log.info("Workflow {} marked as INACTIVE (no active instances)", workflow.getTopic());
            }

            if (needsSave) {
                workflow.setUpdatedAt(Instant.now());
                repository.save(workflow);
            }
        }

        if (totalRemoved > 0) {
            log.info("Cleanup completed: removed {} stale instance(s) total", totalRemoved);
        }

        return totalRemoved;
    }
}

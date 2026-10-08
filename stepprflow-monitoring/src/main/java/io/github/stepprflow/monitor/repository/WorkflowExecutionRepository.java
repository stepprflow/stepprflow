package io.github.stepprflow.monitor.repository;

import io.github.stepprflow.core.model.WorkflowStatus;
import io.github.stepprflow.monitor.model.WorkflowExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * MongoDB repository for workflow executions.
 */
@Repository
public interface WorkflowExecutionRepository extends MongoRepository<WorkflowExecution, String> {

    /**
     * Find by topic.
     */
    Page<WorkflowExecution> findByTopic(String topic, Pageable pageable);

    /**
     * Find by status.
     */
    Page<WorkflowExecution> findByStatus(WorkflowStatus status, Pageable pageable);

    /**
     * Find by multiple statuses.
     */
    Page<WorkflowExecution> findByStatusIn(List<WorkflowStatus> statuses, Pageable pageable);

    /**
     * Find by topic and status.
     */
    Page<WorkflowExecution> findByTopicAndStatus(String topic, WorkflowStatus status, Pageable pageable);

    /**
     * Find by topic and multiple statuses.
     */
    Page<WorkflowExecution> findByTopicAndStatusIn(String topic, List<WorkflowStatus> statuses, Pageable pageable);

    /**
     * Find executions pending retry.
     */
    @Query("{'status': 'RETRY_PENDING', 'retryInfo.nextRetryAt': {'$lte': ?0}}")
    List<WorkflowExecution> findPendingRetries(Instant now);

    /**
     * Deletes executions with the given status whose age exceeds the given
     * cutoff. Age is based on {@code updatedAt}, falling back to
     * {@code createdAt} when {@code updatedAt} is absent or null. Executed
     * server-side by MongoDB (no in-memory loading of matched documents).
     *
     * @param status the execution status to target
     * @param cutoff executions older than this instant are deleted
     * @return the number of deleted documents
     */
    @Query(value = "{ 'status': ?0, '$or': [ "
            + "{ 'updatedAt': { '$lt': ?1 } }, "
            + "{ 'updatedAt': null, 'createdAt': { '$lt': ?1 } } "
            + "] }", delete = true)
    long deleteByStatusAndAgeBefore(WorkflowStatus status, Instant cutoff);

    /**
     * Deletes executions whose status is NOT among {@code excludedStatuses} and
     * whose age exceeds the given cutoff. Age is based on {@code updatedAt},
     * falling back to {@code createdAt} when {@code updatedAt} is absent or
     * null. Used to purge stuck executions ({@code IN_PROGRESS}/
     * {@code PENDING}/{@code RETRY_PENDING}) and {@code CANCELLED} ones,
     * regardless of status, server-side (no in-memory loading).
     *
     * @param excludedStatuses statuses handled by their own dedicated TTL and skipped here
     * @param cutoff executions older than this instant are deleted
     * @return the number of deleted documents
     */
    @Query(value = "{ 'status': { '$nin': ?0 }, '$or': [ "
            + "{ 'updatedAt': { '$lt': ?1 } }, "
            + "{ 'updatedAt': null, 'createdAt': { '$lt': ?1 } } "
            + "] }", delete = true)
    long deleteByStatusNotInAndAgeBefore(List<WorkflowStatus> excludedStatuses, Instant cutoff);

    /**
     * Count by status.
     */
    long countByStatus(WorkflowStatus status);

    /**
     * Count by topic and status.
     */
    long countByTopicAndStatus(String topic, WorkflowStatus status);

    /**
     * Find recent executions.
     */
    List<WorkflowExecution> findTop10ByOrderByCreatedAtDesc();

    /**
     * Find by correlation ID.
     */
    List<WorkflowExecution> findByCorrelationId(String correlationId);
}

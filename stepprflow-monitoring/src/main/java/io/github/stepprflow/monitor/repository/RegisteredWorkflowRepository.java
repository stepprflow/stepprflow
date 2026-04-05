package io.github.stepprflow.monitor.repository;

import io.github.stepprflow.monitor.model.RegisteredWorkflow;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB repository for registered workflow definitions.
 */
@Repository
public interface RegisteredWorkflowRepository extends MongoRepository<RegisteredWorkflow, String> {

    /**
     * Find by topic.
     */
    Optional<RegisteredWorkflow> findByTopic(String topic);

    /**
     * Find by topic and service name (composite key).
     */
    Optional<RegisteredWorkflow> findByTopicAndServiceName(String topic, String serviceName);

    /**
     * Check if topic exists.
     */
    boolean existsByTopic(String topic);

    /**
     * Find workflows updated after a given time.
     */
    List<RegisteredWorkflow> findByUpdatedAtAfter(Instant time);

    /**
     * Find workflows by service name.
     */
    List<RegisteredWorkflow> findByRegisteredByServiceName(String serviceName);

    /**
     * Delete all workflows with the given status.
     *
     * @param status the status to match
     * @return the number of deleted documents
     */
    long deleteByStatus(RegisteredWorkflow.Status status);
}

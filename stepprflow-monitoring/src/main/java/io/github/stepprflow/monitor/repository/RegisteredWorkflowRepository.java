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
     *
     * <p><strong>Warning:</strong> the unique index on this collection is the
     * compound key {@code (topic, serviceName)}, not {@code topic} alone. A
     * topic legitimately registered by several services (e.g. several
     * producers publishing on the same shared topic) has several matching
     * documents, so this derived query throws
     * {@code IncorrectResultSizeDataAccessException} as soon as more than one
     * service has registered the same topic. Use {@link #findFirstByTopic}
     * when any registration for the topic is acceptable.</p>
     */
    Optional<RegisteredWorkflow> findByTopic(String topic);

    /**
     * Find the first registered workflow for a topic, regardless of which
     * service registered it.
     * <p>
     * Unlike {@link #findByTopic}, this never throws when the topic is
     * registered by multiple services: the step count for a given topic is
     * identical across every service that registers it, so taking the first
     * match is correct.
     * </p>
     */
    Optional<RegisteredWorkflow> findFirstByTopic(String topic);

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

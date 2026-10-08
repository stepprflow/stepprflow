package io.github.stepprflow.monitor.service;

import io.github.stepprflow.core.model.WorkflowStatus;
import io.github.stepprflow.monitor.MonitorProperties;
import io.github.stepprflow.monitor.repository.WorkflowExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Purges workflow executions older than the configured retention window.
 *
 * <p>Unlike status-specific TTLs alone, the purge is primarily AGE-based and
 * applies regardless of status: stuck executions ({@code IN_PROGRESS},
 * {@code PENDING}, {@code RETRY_PENDING}) and {@code CANCELLED} executions are
 * purged using {@link MonitorProperties.Retention#getMaxAge()} (default 30
 * days), while {@code COMPLETED} and {@code FAILED} executions may use an
 * optional dedicated override, falling back to the same default otherwise.
 * Deletion is executed server-side by MongoDB (no in-memory loading of
 * matched documents).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetentionService {

    private static final List<WorkflowStatus> TTL_OVERRIDE_STATUSES =
            List.of(WorkflowStatus.COMPLETED, WorkflowStatus.FAILED);

    private final WorkflowExecutionRepository repository;
    private final MonitorProperties properties;

    /**
     * Scheduled entry point. Skips the purge entirely when
     * {@code stepprflow.monitor.retention.enabled} is {@code false}.
     */
    @Scheduled(cron = "${stepprflow.monitor.retention.cleanup-cron:0 0 2 * * ?}")
    public void scheduledPurge() {
        if (!properties.getRetention().isEnabled()) {
            log.debug("Execution retention purge is disabled "
                    + "(stepprflow.monitor.retention.enabled=false); skipping");
            return;
        }
        purgeExpiredExecutions();
    }

    /**
     * Purges expired executions regardless of the {@code enabled} flag. Used
     * by the scheduled job (when enabled) and by manual triggers (e.g. an
     * admin endpoint), which always run on demand.
     *
     * @return the total number of deleted documents for this pass
     */
    public long purgeExpiredExecutions() {
        MonitorProperties.Retention retention = properties.getRetention();
        Instant now = Instant.now();

        long deletedCompleted = repository.deleteByStatusAndAgeBefore(
                WorkflowStatus.COMPLETED, now.minus(retention.effectiveCompletedTtl()));
        long deletedFailed = repository.deleteByStatusAndAgeBefore(
                WorkflowStatus.FAILED, now.minus(retention.effectiveFailedTtl()));
        long deletedOthers = repository.deleteByStatusNotInAndAgeBefore(
                TTL_OVERRIDE_STATUSES, now.minus(retention.effectiveMaxAge()));

        long total = deletedCompleted + deletedFailed + deletedOthers;
        log.info("Retention purge deleted {} old workflow executions "
                + "(completed={}, failed={}, other-statuses={})",
                total, deletedCompleted, deletedFailed, deletedOthers);
        return total;
    }
}

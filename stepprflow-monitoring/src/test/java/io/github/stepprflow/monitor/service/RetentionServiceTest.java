package io.github.stepprflow.monitor.service;

import io.github.stepprflow.core.model.WorkflowStatus;
import io.github.stepprflow.monitor.MonitorProperties;
import io.github.stepprflow.monitor.repository.WorkflowExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RetentionService Tests")
class RetentionServiceTest {

    @Mock
    private WorkflowExecutionRepository repository;

    @Mock
    private MonitorProperties properties;

    @InjectMocks
    private RetentionService retentionService;

    @Captor
    private ArgumentCaptor<Instant> cutoffCaptor;

    @Captor
    private ArgumentCaptor<List<WorkflowStatus>> excludedStatusesCaptor;

    private MonitorProperties.Retention retention;

    @BeforeEach
    void setUp() {
        retention = new MonitorProperties.Retention();
        when(properties.getRetention()).thenReturn(retention);
    }

    @Nested
    @DisplayName("purgeExpiredExecutions() method")
    class PurgeExpiredExecutionsTests {

        @Test
        @DisplayName("Should purge COMPLETED, FAILED and all other statuses by age")
        void shouldPurgeAllStatusBucketsByAge() {
            when(repository.deleteByStatusAndAgeBefore(eq(WorkflowStatus.COMPLETED), any())).thenReturn(3L);
            when(repository.deleteByStatusAndAgeBefore(eq(WorkflowStatus.FAILED), any())).thenReturn(2L);
            when(repository.deleteByStatusNotInAndAgeBefore(anyList(), any())).thenReturn(5L);

            long deleted = retentionService.purgeExpiredExecutions();

            assertThat(deleted).isEqualTo(10L);
            verify(repository).deleteByStatusAndAgeBefore(eq(WorkflowStatus.COMPLETED), any());
            verify(repository).deleteByStatusAndAgeBefore(eq(WorkflowStatus.FAILED), any());
            verify(repository).deleteByStatusNotInAndAgeBefore(anyList(), any());
        }

        @Test
        @DisplayName("Should purge stuck IN_PROGRESS/PENDING/RETRY_PENDING and CANCELLED executions by age (F2/F11)")
        void shouldPurgeStuckAndCancelledStatusesRegardlessOfStatus() {
            when(repository.deleteByStatusAndAgeBefore(any(), any())).thenReturn(0L);
            when(repository.deleteByStatusNotInAndAgeBefore(excludedStatusesCaptor.capture(), any())).thenReturn(4L);

            retentionService.purgeExpiredExecutions();

            // The "everyone else" bucket must only exclude COMPLETED/FAILED (which
            // have their own dedicated TTL bucket); IN_PROGRESS, PENDING,
            // RETRY_PENDING and CANCELLED all fall into this bucket and get purged.
            List<WorkflowStatus> excluded = excludedStatusesCaptor.getValue();
            assertThat(excluded).containsExactlyInAnyOrder(WorkflowStatus.COMPLETED, WorkflowStatus.FAILED);
            assertThat(excluded).doesNotContain(
                    WorkflowStatus.IN_PROGRESS, WorkflowStatus.PENDING,
                    WorkflowStatus.RETRY_PENDING, WorkflowStatus.CANCELLED);
        }

        @Test
        @DisplayName("Should default to 30 days for the 'everyone else' bucket when maxAge is unset")
        void shouldDefaultToThirtyDaysForOtherStatuses() {
            when(repository.deleteByStatusAndAgeBefore(any(), any())).thenReturn(0L);
            when(repository.deleteByStatusNotInAndAgeBefore(anyList(), cutoffCaptor.capture())).thenReturn(0L);

            retentionService.purgeExpiredExecutions();

            Instant expectedCutoff = Instant.now().minus(Duration.ofDays(30));
            assertThat(cutoffCaptor.getValue()).isBetween(expectedCutoff.minusSeconds(2), expectedCutoff.plusSeconds(2));
        }

        @Test
        @DisplayName("Should use completedTtl override when set, otherwise fall back to maxAge")
        void shouldUseCompletedTtlOverrideWhenSet() {
            retention.setMaxAge(Duration.ofDays(30));
            retention.setCompletedTtl(Duration.ofDays(5));
            when(repository.deleteByStatusAndAgeBefore(eq(WorkflowStatus.COMPLETED), cutoffCaptor.capture()))
                    .thenReturn(0L);
            when(repository.deleteByStatusAndAgeBefore(eq(WorkflowStatus.FAILED), any())).thenReturn(0L);
            when(repository.deleteByStatusNotInAndAgeBefore(anyList(), any())).thenReturn(0L);

            retentionService.purgeExpiredExecutions();

            Instant expectedCutoff = Instant.now().minus(Duration.ofDays(5));
            assertThat(cutoffCaptor.getValue()).isBetween(expectedCutoff.minusSeconds(2), expectedCutoff.plusSeconds(2));
        }

        @Test
        @DisplayName("Should fall back to maxAge for FAILED executions when failedTtl is unset")
        void shouldFallBackToMaxAgeForFailedWhenUnset() {
            retention.setMaxAge(Duration.ofDays(30));
            retention.setFailedTtl(null);
            when(repository.deleteByStatusAndAgeBefore(eq(WorkflowStatus.COMPLETED), any())).thenReturn(0L);
            when(repository.deleteByStatusAndAgeBefore(eq(WorkflowStatus.FAILED), cutoffCaptor.capture()))
                    .thenReturn(0L);
            when(repository.deleteByStatusNotInAndAgeBefore(anyList(), any())).thenReturn(0L);

            retentionService.purgeExpiredExecutions();

            Instant expectedCutoff = Instant.now().minus(Duration.ofDays(30));
            assertThat(cutoffCaptor.getValue()).isBetween(expectedCutoff.minusSeconds(2), expectedCutoff.plusSeconds(2));
        }

        @Test
        @DisplayName("Should purge even when retention.enabled is false (manual trigger bypasses the flag)")
        void shouldPurgeRegardlessOfEnabledFlag() {
            retention.setEnabled(false);
            when(repository.deleteByStatusAndAgeBefore(any(), any())).thenReturn(0L);
            when(repository.deleteByStatusNotInAndAgeBefore(anyList(), any())).thenReturn(0L);

            long deleted = retentionService.purgeExpiredExecutions();

            assertThat(deleted).isZero();
            verify(repository).deleteByStatusAndAgeBefore(eq(WorkflowStatus.COMPLETED), any());
        }
    }

    @Nested
    @DisplayName("scheduledPurge() method")
    class ScheduledPurgeTests {

        @Test
        @DisplayName("Should run the purge when retention.enabled is true")
        void shouldRunPurgeWhenEnabled() {
            retention.setEnabled(true);
            when(repository.deleteByStatusAndAgeBefore(any(), any())).thenReturn(0L);
            when(repository.deleteByStatusNotInAndAgeBefore(anyList(), any())).thenReturn(0L);

            retentionService.scheduledPurge();

            verify(repository).deleteByStatusAndAgeBefore(eq(WorkflowStatus.COMPLETED), any());
            verify(repository).deleteByStatusAndAgeBefore(eq(WorkflowStatus.FAILED), any());
            verify(repository).deleteByStatusNotInAndAgeBefore(anyList(), any());
        }

        @Test
        @DisplayName("Should skip the purge entirely when retention.enabled is false")
        void shouldSkipPurgeWhenDisabled() {
            retention.setEnabled(false);

            retentionService.scheduledPurge();

            verifyNoInteractions(repository);
        }
    }
}

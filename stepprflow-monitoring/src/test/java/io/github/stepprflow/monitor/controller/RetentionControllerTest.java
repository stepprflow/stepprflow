package io.github.stepprflow.monitor.controller;

import io.github.stepprflow.monitor.service.RetentionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for RetentionController.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RetentionController Tests")
class RetentionControllerTest {

    @Mock
    private RetentionService retentionService;

    private RetentionController controller;

    @BeforeEach
    void setUp() {
        controller = new RetentionController(retentionService);
    }

    @Nested
    @DisplayName("DELETE /executions/retention - purgeExpiredExecutions()")
    class PurgeExpiredExecutionsTests {

        @Test
        @DisplayName("should return 200 with purged count")
        void shouldReturn200WithPurgedCount() {
            // Given
            when(retentionService.purgeExpiredExecutions()).thenReturn(42L);

            // When
            ResponseEntity<Map<String, Long>> response = controller.purgeExpiredExecutions();

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("purgedCount", 42L);
            verify(retentionService).purgeExpiredExecutions();
        }

        @Test
        @DisplayName("should return 200 with zero count when nothing to purge")
        void shouldReturn200WithZeroCount() {
            // Given
            when(retentionService.purgeExpiredExecutions()).thenReturn(0L);

            // When
            ResponseEntity<Map<String, Long>> response = controller.purgeExpiredExecutions();

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("purgedCount", 0L);
        }

        @Test
        @DisplayName("should trigger an immediate purge regardless of retention.enabled (manual override)")
        void shouldDelegateDirectlyToPurgeExpiredExecutions() {
            // When
            controller.purgeExpiredExecutions();

            // Then: the manual trigger always calls the unconditional purge method,
            // never the enabled-gated scheduledPurge() one.
            verify(retentionService).purgeExpiredExecutions();
        }
    }
}

package io.github.stepprflow.monitor.controller;

import io.github.stepprflow.monitor.service.RetentionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin API to manually trigger a retention purge of workflow executions,
 * outside of the scheduled cron job. Requires the {@code OPERATOR} authority
 * (DELETE under {@code /api/**} is gated by {@code MonitorSecurityConfig}).
 */
@RestController
@RequestMapping("/api/executions")
@RequiredArgsConstructor
@Tag(name = "Retention", description = "Manual retention/purge of workflow executions")
public class RetentionController {

    private final RetentionService retentionService;

    @Operation(
            summary = "Purge expired executions now",
            description = "Immediately purges executions older than the configured retention window, "
                    + "across all statuses, bypassing the retention.enabled flag used by the scheduled job.")
    @ApiResponse(responseCode = "200", description = "Purge completed, returns the number of deleted executions")
    @DeleteMapping("/retention")
    public ResponseEntity<Map<String, Long>> purgeExpiredExecutions() {
        long purgedCount = retentionService.purgeExpiredExecutions();
        return ResponseEntity.ok(Map.of("purgedCount", purgedCount));
    }
}

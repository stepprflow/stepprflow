package io.github.stepprflow.monitor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stepprflow.core.model.WorkflowStatus;
import io.github.stepprflow.monitor.model.WorkflowExecution;
import io.github.stepprflow.monitor.service.PayloadManagementService;
import io.github.stepprflow.monitor.service.WorkflowCommandService;
import io.github.stepprflow.monitor.service.WorkflowQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Validation tests for WorkflowController using MockMvc.
 * Tests input validation on request parameters and body.
 */
@WebMvcTest(WorkflowController.class)
// Validation-focused slice: disable the security filters (auth is covered by
// the dedicated security test) so these tests exercise request validation only.
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {WorkflowController.class, GlobalExceptionHandler.class})
@DisplayName("WorkflowController Validation Tests")
class WorkflowControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WorkflowQueryService queryService;

    @MockBean
    private WorkflowCommandService commandService;

    @MockBean
    private PayloadManagementService payloadService;

    @Nested
    @DisplayName("Payload Update Validation")
    class PayloadUpdateValidationTests {

        @Test
        @DisplayName("Should reject empty fieldPath")
        void shouldRejectEmptyFieldPath() throws Exception {
            var request = Map.of(
                    "fieldPath", "",
                    "newValue", "test",
                    "changedBy", "user",
                    "reason", "test reason"
            );

            mockMvc.perform(patch("/api/workflows/exec-123/payload")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should reject null fieldPath")
        void shouldRejectNullFieldPath() throws Exception {
            var request = Map.of(
                    "newValue", "test",
                    "changedBy", "user",
                    "reason", "test reason"
            );

            mockMvc.perform(patch("/api/workflows/exec-123/payload")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should reject fieldPath with only whitespace")
        void shouldRejectWhitespaceFieldPath() throws Exception {
            var request = Map.of(
                    "fieldPath", "   ",
                    "newValue", "test",
                    "changedBy", "user"
            );

            mockMvc.perform(patch("/api/workflows/exec-123/payload")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should reject fieldPath exceeding max length")
        void shouldRejectFieldPathExceedingMaxLength() throws Exception {
            // 256 characters exceeds max length of 255
            String longPath = "a".repeat(256);
            var request = Map.of(
                    "fieldPath", longPath,
                    "newValue", "test",
                    "changedBy", "user"
            );

            mockMvc.perform(patch("/api/workflows/exec-123/payload")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should reject fieldPath with invalid characters (XSS attempt)")
        void shouldRejectFieldPathWithInvalidCharacters() throws Exception {
            var request = Map.of(
                    "fieldPath", "field<script>",
                    "newValue", "test",
                    "changedBy", "user"
            );

            mockMvc.perform(patch("/api/workflows/exec-123/payload")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should accept valid fieldPath with dots")
        void shouldAcceptValidFieldPathWithDots() throws Exception {
            var execution = createTestExecution();
            when(payloadService.updatePayloadField(anyString(), anyString(), any(), anyString(), anyString()))
                    .thenReturn(execution);

            var request = Map.of(
                    "fieldPath", "customer.address.city",
                    "newValue", "Paris",
                    "changedBy", "admin"
            );

            mockMvc.perform(patch("/api/workflows/exec-123/payload")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should accept valid fieldPath with array notation")
        void shouldAcceptValidFieldPathWithArrayNotation() throws Exception {
            var execution = createTestExecution();
            when(payloadService.updatePayloadField(anyString(), anyString(), any(), anyString(), anyString()))
                    .thenReturn(execution);

            var request = Map.of(
                    "fieldPath", "items[0].name",
                    "newValue", "Product",
                    "changedBy", "admin"
            );

            mockMvc.perform(patch("/api/workflows/exec-123/payload")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should reject changedBy exceeding max length")
        void shouldRejectChangedByExceedingMaxLength() throws Exception {
            var request = Map.of(
                    "fieldPath", "field",
                    "newValue", "test",
                    "changedBy", "a".repeat(101)
            );

            mockMvc.perform(patch("/api/workflows/exec-123/payload")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should reject reason exceeding max length")
        void shouldRejectReasonExceedingMaxLength() throws Exception {
            var request = Map.of(
                    "fieldPath", "field",
                    "newValue", "test",
                    "changedBy", "user",
                    "reason", "x".repeat(501)
            );

            mockMvc.perform(patch("/api/workflows/exec-123/payload")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Pagination Validation")
    class PaginationValidationTests {

        @Test
        @DisplayName("Should reject negative page number")
        void shouldRejectNegativePage() throws Exception {
            mockMvc.perform(get("/api/workflows")
                            .param("page", "-1")
                            .param("sort", "createdAt"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should reject page size less than 1")
        void shouldRejectPageSizeLessThanOne() throws Exception {
            mockMvc.perform(get("/api/workflows")
                            .param("size", "0")
                            .param("sort", "createdAt"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should reject page size greater than 100")
        void shouldRejectPageSizeGreaterThanMax() throws Exception {
            mockMvc.perform(get("/api/workflows")
                            .param("size", "101")
                            .param("sort", "createdAt"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should accept valid page and size")
        void shouldAcceptValidPageAndSize() throws Exception {
            when(queryService.findExecutions(any(), any(), any()))
                    .thenReturn(org.springframework.data.domain.Page.empty());

            mockMvc.perform(get("/api/workflows")
                            .param("page", "0")
                            .param("size", "50")
                            .param("sort", "createdAt"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should use default values when not specified")
        void shouldUseDefaultValues() throws Exception {
            when(queryService.findExecutions(any(), any(), any()))
                    .thenReturn(org.springframework.data.domain.Page.empty());

            mockMvc.perform(get("/api/workflows"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should reject invalid sortBy field")
        void shouldRejectInvalidSortByField() throws Exception {
            mockMvc.perform(get("/api/workflows")
                            .param("sort", "invalidField"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should accept valid sortBy fields")
        void shouldAcceptValidSortByFields() throws Exception {
            when(queryService.findExecutions(any(), any(), any()))
                    .thenReturn(org.springframework.data.domain.Page.empty());

            // Test valid sortBy values
            for (String sortBy : new String[]{"createdAt", "updatedAt", "status", "topic", "currentStep"}) {
                mockMvc.perform(get("/api/workflows")
                                .param("sort", sortBy))
                        .andExpect(status().isOk());
            }
        }
    }

    private WorkflowExecution createTestExecution() {
        return WorkflowExecution.builder()
                .executionId("exec-123")
                .correlationId("corr-456")
                .topic("test-topic")
                .status(WorkflowStatus.FAILED)
                .currentStep(1)
                .totalSteps(3)
                .payload(Map.of("field", "value"))
                .createdAt(Instant.now())
                .build();
    }
}

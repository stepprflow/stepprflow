package io.github.stepprflow.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stepprflow.core.StepprFlowProperties;
import io.github.stepprflow.core.model.WorkflowMessage;
import io.github.stepprflow.core.security.TrustedClassResolver;
import io.github.stepprflow.core.security.UntrustedPayloadTypeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PayloadDeserializer Tests")
class PayloadDeserializerTest {

    private PayloadDeserializer deserializer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        StepprFlowProperties props = new StepprFlowProperties();
        // Trust the core model + this test's own package (for the POJO case).
        // Simple JDK types (String/Integer/Map) come from the resolver's baseline.
        props.setTrustedPackages(List.of(
                "io.github.stepprflow.core.model",
                "io.github.stepprflow.core.service"));
        TrustedClassResolver resolver = new TrustedClassResolver(props);
        deserializer = new PayloadDeserializer(objectMapper, resolver);
    }

    @Nested
    @DisplayName("deserialize() method")
    class DeserializeTests {

        @Test
        @DisplayName("Should return null when payload is null")
        void shouldReturnNullWhenPayloadIsNull() throws Exception {
            WorkflowMessage message = WorkflowMessage.builder()
                    .payload(null)
                    .build();

            Object result = deserializer.deserialize(message);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Should return raw payload when payloadType is null")
        void shouldReturnRawPayloadWhenPayloadTypeIsNull() throws Exception {
            Map<String, Object> payload = Map.of("key", "value");
            WorkflowMessage message = WorkflowMessage.builder()
                    .payload(payload)
                    .payloadType(null)
                    .build();

            Object result = deserializer.deserialize(message);

            assertThat(result).isEqualTo(payload);
        }

        @Test
        @DisplayName("Should deserialize to Map type")
        void shouldDeserializeToMapType() throws Exception {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("orderId", "ORD-001");
            payload.put("quantity", 5);

            WorkflowMessage message = WorkflowMessage.builder()
                    .payload(payload)
                    .payloadType("java.util.LinkedHashMap")
                    .build();

            Object result = deserializer.deserialize(message);

            assertThat(result).isInstanceOf(LinkedHashMap.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> resultMap = (Map<String, Object>) result;
            assertThat(resultMap.get("orderId")).isEqualTo("ORD-001");
            assertThat(resultMap.get("quantity")).isEqualTo(5);
        }

        @Test
        @DisplayName("Should deserialize to custom POJO type")
        void shouldDeserializeToCustomPojoType() throws Exception {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("name", "John");
            payload.put("age", 30);

            WorkflowMessage message = WorkflowMessage.builder()
                    .payload(payload)
                    .payloadType(TestPayload.class.getName())
                    .build();

            Object result = deserializer.deserialize(message);

            assertThat(result).isInstanceOf(TestPayload.class);
            TestPayload testPayload = (TestPayload) result;
            assertThat(testPayload.getName()).isEqualTo("John");
            assertThat(testPayload.getAge()).isEqualTo(30);
        }

        @Test
        @DisplayName("Should return raw payload when a TRUSTED class is not found")
        void shouldReturnRawPayloadWhenTrustedClassNotFound() throws Exception {
            Map<String, Object> payload = Map.of("key", "value");
            WorkflowMessage message = WorkflowMessage.builder()
                    .payload(payload)
                    // trusted package, but the class does not exist -> CNF -> raw
                    .payloadType("io.github.stepprflow.core.model.NoSuchPayload")
                    .build();

            Object result = deserializer.deserialize(message);

            assertThat(result).isEqualTo(payload);
        }

        @Test
        @DisplayName("Should reject an untrusted payloadType (SF-2)")
        void shouldRejectUntrustedPayloadType() {
            Map<String, Object> payload = Map.of("key", "value");
            WorkflowMessage message = WorkflowMessage.builder()
                    .payload(payload)
                    .payloadType("com.evil.Gadget")
                    .build();

            assertThatThrownBy(() -> deserializer.deserialize(message))
                    .isInstanceOf(UntrustedPayloadTypeException.class);
        }

        @Test
        @DisplayName("Should handle String payload")
        void shouldHandleStringPayload() throws Exception {
            WorkflowMessage message = WorkflowMessage.builder()
                    .payload("simple string")
                    .payloadType("java.lang.String")
                    .build();

            Object result = deserializer.deserialize(message);

            assertThat(result).isEqualTo("simple string");
        }

        @Test
        @DisplayName("Should handle Integer payload")
        void shouldHandleIntegerPayload() throws Exception {
            WorkflowMessage message = WorkflowMessage.builder()
                    .payload(42)
                    .payloadType("java.lang.Integer")
                    .build();

            Object result = deserializer.deserialize(message);

            assertThat(result).isEqualTo(42);
        }
    }

    // Test POJO class
    public static class TestPayload {
        private String name;
        private int age;

        public TestPayload() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
    }
}

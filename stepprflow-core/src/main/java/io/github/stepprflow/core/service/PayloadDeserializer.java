package io.github.stepprflow.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stepprflow.core.model.WorkflowMessage;
import io.github.stepprflow.core.security.TrustedClassResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Service for deserializing workflow message payloads to their original types.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PayloadDeserializer {

    /** The JSON object mapper. */
    private final ObjectMapper objectMapper;

    /** Resolves a payloadType to a class only if its package is trusted. */
    private final TrustedClassResolver trustedClassResolver;

    /**
     * Deserialize the payload from a workflow message to its original type.
     *
     * @param message the workflow message containing the payload
     * @return the deserialized payload object, or null if payload is null
     * @throws Exception if deserialization fails
     */
    public Object deserialize(final WorkflowMessage message) throws Exception {
        if (message.getPayload() == null) {
            return null;
        }

        String payloadType = message.getPayloadType();
        if (payloadType == null) {
            return message.getPayload();
        }

        try {
            // SECURITY: only resolves the class if its package is trusted;
            // an untrusted payloadType throws UntrustedPayloadTypeException,
            // which is NOT caught here and propagates to the caller.
            Class<?> payloadClass =
                    trustedClassResolver.loadTrustedClass(payloadType);
            return objectMapper.convertValue(message.getPayload(), payloadClass);
        } catch (ClassNotFoundException e) {
            log.warn("Could not find payload class {}, using raw payload",
                     payloadType);
            return message.getPayload();
        }
    }
}

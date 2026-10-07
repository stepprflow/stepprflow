package io.github.stepprflow.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stepprflow.core.StepprFlowProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SecurityContextSigner Tests (SF-3)")
class SecurityContextSignerTest {

    private static SecurityContextSigner signerWithSecret(final String secret) {
        StepprFlowProperties props = new StepprFlowProperties();
        props.getSecurity().getContextSigning().setSecret(secret);
        return new SecurityContextSigner(props);
    }

    private static SecurityContextSigner disabledSigner() {
        // No secret configured.
        return new SecurityContextSigner(new StepprFlowProperties());
    }

    @Nested
    @DisplayName("when signing is enabled")
    class Enabled {

        private final SecurityContextSigner signer =
                signerWithSecret("a-shared-hmac-secret-value");

        @Test
        @DisplayName("round-trips a signed context")
        void roundTrip() {
            String wrapped = signer.wrap("exec-1", "topic-1", "the-context");
            assertThat(wrapped).isNotEqualTo("the-context").contains(".");
            assertThat(signer.unwrapAndVerify("exec-1", "topic-1", wrapped))
                    .isEqualTo("the-context");
        }

        @Test
        @DisplayName("rejects a tampered context (signature mismatch)")
        void rejectsTampered() {
            String wrapped = signer.wrap("exec-1", "topic-1", "the-context");
            String sig = wrapped.substring(0, wrapped.indexOf('.'));
            assertThatThrownBy(() ->
                    signer.unwrapAndVerify("exec-1", "topic-1", sig + ".evil-raw"))
                    .isInstanceOf(ForgedSecurityContextException.class);
        }

        @Test
        @DisplayName("rejects replay onto a different executionId")
        void rejectsReplayDifferentExecution() {
            String wrapped = signer.wrap("exec-1", "topic-1", "the-context");
            assertThatThrownBy(() ->
                    signer.unwrapAndVerify("exec-2", "topic-1", wrapped))
                    .isInstanceOf(ForgedSecurityContextException.class);
        }

        @Test
        @DisplayName("rejects replay onto a different topic")
        void rejectsReplayDifferentTopic() {
            String wrapped = signer.wrap("exec-1", "topic-1", "the-context");
            assertThatThrownBy(() ->
                    signer.unwrapAndVerify("exec-1", "topic-2", wrapped))
                    .isInstanceOf(ForgedSecurityContextException.class);
        }

        @Test
        @DisplayName("rejects a context with no signature envelope")
        void rejectsMissingSignature() {
            assertThatThrownBy(() ->
                    signer.unwrapAndVerify("exec-1", "topic-1", "unsigned-raw"))
                    .isInstanceOf(ForgedSecurityContextException.class);
        }

        @Test
        @DisplayName("is enabled and passes through null")
        void nullIsNull() {
            assertThat(signer.isEnabled()).isTrue();
            assertThat(signer.wrap("exec-1", "topic-1", null)).isNull();
            assertThat(signer.unwrapAndVerify("exec-1", "topic-1", null)).isNull();
        }
    }

    @Nested
    @DisplayName("when signing is disabled (no secret)")
    class Disabled {

        private final SecurityContextSigner signer = disabledSigner();

        @Test
        @DisplayName("is a pass-through for wrap and unwrap")
        void passThrough() {
            assertThat(signer.isEnabled()).isFalse();
            assertThat(signer.wrap("exec-1", "topic-1", "ctx")).isEqualTo("ctx");
            assertThat(signer.unwrapAndVerify("exec-1", "topic-1", "ctx"))
                    .isEqualTo("ctx");
            assertThat(signer.wrap("exec-1", "topic-1", null)).isNull();
            assertThat(signer.unwrapAndVerify("exec-1", "topic-1", null)).isNull();
        }
    }
}

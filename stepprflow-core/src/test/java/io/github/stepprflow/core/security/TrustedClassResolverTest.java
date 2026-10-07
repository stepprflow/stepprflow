package io.github.stepprflow.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stepprflow.core.StepprFlowProperties;
import io.github.stepprflow.core.model.WorkflowMessage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TrustedClassResolver Tests (SF-2)")
class TrustedClassResolverTest {

    private TrustedClassResolver resolverWith(final List<String> corePackages) {
        StepprFlowProperties props = new StepprFlowProperties();
        props.setTrustedPackages(corePackages);
        // Keep the per-broker defaults (core model) so the union is well-formed.
        return new TrustedClassResolver(props);
    }

    @Nested
    @DisplayName("isTrusted")
    class IsTrusted {

        @Test
        @DisplayName("accepts a class under a trusted package")
        void acceptsTrusted() {
            TrustedClassResolver r =
                    resolverWith(List.of("io.github.stepprflow.core.model"));
            assertThat(r.isTrusted(
                    "io.github.stepprflow.core.model.WorkflowMessage")).isTrue();
        }

        @Test
        @DisplayName("rejects a class outside every trusted package")
        void rejectsUntrusted() {
            TrustedClassResolver r =
                    resolverWith(List.of("io.github.stepprflow.core.model"));
            assertThat(r.isTrusted("com.evil.Gadget")).isFalse();
            assertThat(r.isTrusted(
                    "org.springframework.context.support.ClassPathXmlApplicationContext"))
                    .isFalse();
        }

        @Test
        @DisplayName("enforces a package boundary (no prefix escape)")
        void enforcesPackageBoundary() {
            TrustedClassResolver r = resolverWith(List.of("com.foo"));
            assertThat(r.isTrusted("com.foo.Payload")).isTrue();
            // 'com.foobar' must NOT be trusted by 'com.foo'
            assertThat(r.isTrusted("com.foobar.Gadget")).isFalse();
        }

        @Test
        @DisplayName("rejects null/blank payloadType")
        void rejectsNullOrBlank() {
            TrustedClassResolver r = resolverWith(List.of("com.foo"));
            assertThat(r.isTrusted(null)).isFalse();
            assertThat(r.isTrusted("  ")).isFalse();
        }

        @Test
        @DisplayName("unions the core and per-broker trusted packages")
        void unionsCoreAndBroker() {
            StepprFlowProperties props = new StepprFlowProperties();
            props.setTrustedPackages(List.of("io.github.stepprflow.core.model"));
            props.getKafka().setTrustedPackages(List.of("com.acme.kafkapayload"));
            props.getRabbitmq().setTrustedPackages(List.of("com.acme.rabbitpayload"));
            TrustedClassResolver r = new TrustedClassResolver(props);
            assertThat(r.isTrusted("com.acme.kafkapayload.A")).isTrue();
            assertThat(r.isTrusted("com.acme.rabbitpayload.B")).isTrue();
            assertThat(r.isTrusted("io.github.stepprflow.core.model.WorkflowMessage"))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("loadTrustedClass")
    class LoadTrustedClass {

        @Test
        @DisplayName("loads a trusted, resolvable class")
        void loadsTrusted() throws Exception {
            TrustedClassResolver r =
                    resolverWith(List.of("io.github.stepprflow.core.model"));
            assertThat(r.loadTrustedClass(
                    "io.github.stepprflow.core.model.WorkflowMessage"))
                    .isEqualTo(WorkflowMessage.class);
        }

        @Test
        @DisplayName("throws UntrustedPayloadTypeException for an untrusted class")
        void throwsForUntrusted() {
            TrustedClassResolver r =
                    resolverWith(List.of("io.github.stepprflow.core.model"));
            assertThatThrownBy(() -> r.loadTrustedClass("com.evil.Gadget"))
                    .isInstanceOf(UntrustedPayloadTypeException.class);
        }

        @Test
        @DisplayName("allows safe JDK baseline types (String) without extra config")
        void allowsJdkBaseline() throws Exception {
            TrustedClassResolver r =
                    resolverWith(List.of("io.github.stepprflow.core.model"));
            assertThat(r.loadTrustedClass("java.lang.String"))
                    .isEqualTo(String.class);
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("rejects a wildcard in the configured trusted packages")
        void rejectsWildcardConfig() {
            StepprFlowProperties props = new StepprFlowProperties();
            props.setTrustedPackages(List.of("*"));
            assertThatThrownBy(() -> new TrustedClassResolver(props))
                    .isInstanceOf(SecurityException.class);
        }
    }
}

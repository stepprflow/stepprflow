package io.github.stepprflow.core.service;

import io.github.stepprflow.core.model.WorkflowMessage;
import io.github.stepprflow.core.model.WorkflowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("CallbackMethodInvoker Tests")
class CallbackMethodInvokerTest {

    @Mock
    private PayloadDeserializer payloadDeserializer;

    private CallbackMethodInvoker invoker;
    private WorkflowMessage testMessage;
    private TestCallbackHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        invoker = new CallbackMethodInvoker(payloadDeserializer);
        handler = new TestCallbackHandler();

        testMessage = WorkflowMessage.builder()
                .executionId("exec-123")
                .correlationId("corr-456")
                .topic("test-topic")
                .currentStep(1)
                .totalSteps(3)
                .status(WorkflowStatus.IN_PROGRESS)
                .payload(Map.of("key", "value"))
                .createdAt(Instant.now())
                .build();

        // Configure payloadDeserializer to return the raw payload by default
        lenient().when(payloadDeserializer.deserialize(any()))
                .thenAnswer(invocation -> {
                    WorkflowMessage msg = invocation.getArgument(0);
                    return msg.getPayload();
                });
    }

    @Nested
    @DisplayName("invoke() with no parameters")
    class NoParameterTests {

        @Test
        @DisplayName("Should invoke callback with no parameters")
        void shouldInvokeCallbackWithNoParameters() throws Exception {
            Method method = TestCallbackHandler.class.getDeclaredMethod("onSuccessNoArgs");

            invoker.invoke(method, handler, testMessage, null);

            assertThat(handler.noArgsCalled).isTrue();
        }
    }

    @Nested
    @DisplayName("invoke() with one parameter")
    class OneParameterTests {

        @Test
        @DisplayName("Should invoke callback with WorkflowMessage parameter")
        void shouldInvokeWithWorkflowMessage() throws Exception {
            Method method = TestCallbackHandler.class.getDeclaredMethod("onSuccessWithMessage", WorkflowMessage.class);

            invoker.invoke(method, handler, testMessage, null);

            assertThat(handler.messageReceived).isEqualTo(testMessage);
        }

        @Test
        @DisplayName("Should invoke callback with Throwable parameter")
        void shouldInvokeWithThrowable() throws Exception {
            Method method = TestCallbackHandler.class.getDeclaredMethod("onFailureWithError", Throwable.class);
            RuntimeException error = new RuntimeException("Test error");

            invoker.invoke(method, handler, testMessage, error);

            assertThat(handler.errorReceived).isEqualTo(error);
        }

        @Test
        @DisplayName("Should invoke callback with payload parameter")
        void shouldInvokeWithPayload() throws Exception {
            Method method = TestCallbackHandler.class.getDeclaredMethod("onSuccessWithPayload", Object.class);

            invoker.invoke(method, handler, testMessage, null);

            assertThat(handler.payloadReceived).isEqualTo(testMessage.getPayload());
        }

        @Test
        @DisplayName("SF-27: a Throwable param on the success path receives null, not a mis-injected payload")
        void shouldInvokeThrowableParamWithNullOnSuccess() throws Exception {
            Method method = TestCallbackHandler.class.getDeclaredMethod("onFailureWithError", Throwable.class);

            // error == null (success path). Previously this fell through and
            // passed the Map payload to a Throwable parameter -> ClassCastException.
            invoker.invoke(method, handler, testMessage, null);

            assertThat(handler.onFailureWithErrorCalled).isTrue();
            assertThat(handler.errorReceived).isNull();
            assertThat(handler.payloadReceived).isNull();
        }

        @Test
        @DisplayName("Should prefer Throwable over payload when error is provided")
        void shouldPreferThrowableOverPayload() throws Exception {
            Method method = TestCallbackHandler.class.getDeclaredMethod("onFailureWithError", Throwable.class);
            RuntimeException error = new RuntimeException("Test error");

            invoker.invoke(method, handler, testMessage, error);

            assertThat(handler.errorReceived).isEqualTo(error);
        }
    }

    @Nested
    @DisplayName("invoke() with two parameters")
    class TwoParameterTests {

        @Test
        @DisplayName("Should invoke callback with (WorkflowMessage, Throwable)")
        void shouldInvokeWithMessageAndError() throws Exception {
            Method method = TestCallbackHandler.class.getDeclaredMethod("onFailureWithMessageAndError", WorkflowMessage.class, Throwable.class);
            RuntimeException error = new RuntimeException("Test error");

            invoker.invoke(method, handler, testMessage, error);

            assertThat(handler.messageReceived).isEqualTo(testMessage);
            assertThat(handler.errorReceived).isEqualTo(error);
        }

        @Test
        @DisplayName("Should invoke callback with (payload, Throwable)")
        void shouldInvokeWithPayloadAndError() throws Exception {
            Method method = TestCallbackHandler.class.getDeclaredMethod("onFailureWithPayloadAndError", Object.class, Throwable.class);
            RuntimeException error = new RuntimeException("Test error");

            invoker.invoke(method, handler, testMessage, error);

            assertThat(handler.payloadReceived).isEqualTo(testMessage.getPayload());
            assertThat(handler.errorReceived).isEqualTo(error);
        }

        @Test
        @DisplayName("Should pass null for second parameter when not Throwable type")
        void shouldPassNullForNonThrowableSecondParam() throws Exception {
            Method method = TestCallbackHandler.class.getDeclaredMethod("onSuccessWithPayloadAndString", Object.class, String.class);

            invoker.invoke(method, handler, testMessage, null);

            assertThat(handler.payloadReceived).isEqualTo(testMessage.getPayload());
            assertThat(handler.stringReceived).isNull();
        }
    }

    @Nested
    @DisplayName("invoke() with unsupported parameters")
    class UnsupportedParameterTests {

        @Test
        @DisplayName("Should not throw for methods with 3+ parameters")
        void shouldNotThrowForTooManyParameters() throws Exception {
            Method method = TestCallbackHandler.class.getDeclaredMethod("unsupportedThreeParams", Object.class, Throwable.class, String.class);

            // Should not throw, just log a warning
            invoker.invoke(method, handler, testMessage, new RuntimeException("error"));

            // Method should not be invoked
            assertThat(handler.unsupportedCalled).isFalse();
        }
    }

    @Nested
    @DisplayName("invokeRaw() without deserializer")
    class InvokeRawTests {

        @Test
        @DisplayName("Should invoke callback with no parameters")
        void shouldInvokeWithNoParameters() throws Exception {
            Method method = TestCallbackHandler.class.getDeclaredMethod("onSuccessNoArgs");

            invoker.invokeRaw(method, handler, testMessage, null);

            assertThat(handler.noArgsCalled).isTrue();
        }

        @Test
        @DisplayName("Should use raw payload from message")
        void shouldUseRawPayload() throws Exception {
            Method method = TestCallbackHandler.class.getDeclaredMethod("onSuccessWithPayload", Object.class);

            invoker.invokeRaw(method, handler, testMessage, null);

            assertThat(handler.payloadReceived).isEqualTo(testMessage.getPayload());
        }

        @Test
        @DisplayName("Should invoke with WorkflowMessage parameter")
        void shouldInvokeWithWorkflowMessage() throws Exception {
            Method method = TestCallbackHandler.class.getDeclaredMethod("onSuccessWithMessage", WorkflowMessage.class);

            invoker.invokeRaw(method, handler, testMessage, null);

            assertThat(handler.messageReceived).isEqualTo(testMessage);
        }

        @Test
        @DisplayName("Should work with (payload, error) pattern using raw payload")
        void shouldWorkWithPayloadAndErrorRaw() throws Exception {
            Method method = TestCallbackHandler.class.getDeclaredMethod("onFailureWithPayloadAndError", Object.class, Throwable.class);
            RuntimeException error = new RuntimeException("Test error");

            invoker.invokeRaw(method, handler, testMessage, error);

            assertThat(handler.payloadReceived).isEqualTo(testMessage.getPayload());
            assertThat(handler.errorReceived).isEqualTo(error);
        }

        @Test
        @DisplayName("Should not throw for methods with 3+ parameters")
        void shouldNotThrowForUnsupportedParameters() throws Exception {
            Method method = TestCallbackHandler.class.getDeclaredMethod("unsupportedThreeParams", Object.class, Throwable.class, String.class);

            // Should not throw, just log a warning
            invoker.invokeRaw(method, handler, testMessage, new RuntimeException("error"));

            // Method should not be invoked
            assertThat(handler.unsupportedCalled).isFalse();
        }
    }

    // Test callback handler class
    public static class TestCallbackHandler {
        boolean noArgsCalled = false;
        WorkflowMessage messageReceived = null;
        Throwable errorReceived = null;
        boolean onFailureWithErrorCalled = false;
        Object payloadReceived = null;
        String stringReceived = null;
        boolean unsupportedCalled = false;

        public void onSuccessNoArgs() {
            noArgsCalled = true;
        }

        public void onSuccessWithMessage(WorkflowMessage message) {
            messageReceived = message;
        }

        public void onFailureWithError(Throwable error) {
            onFailureWithErrorCalled = true;
            errorReceived = error;
        }

        public void onSuccessWithPayload(Object payload) {
            payloadReceived = payload;
        }

        public void onFailureWithMessageAndError(WorkflowMessage message, Throwable error) {
            messageReceived = message;
            errorReceived = error;
        }

        public void onFailureWithPayloadAndError(Object payload, Throwable error) {
            payloadReceived = payload;
            errorReceived = error;
        }

        public void onSuccessWithPayloadAndString(Object payload, String str) {
            payloadReceived = payload;
            stringReceived = str;
        }

        public void unsupportedThreeParams(Object payload, Throwable error, String extra) {
            unsupportedCalled = true;
        }
    }
}

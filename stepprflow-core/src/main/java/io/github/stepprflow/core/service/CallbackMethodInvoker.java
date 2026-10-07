package io.github.stepprflow.core.service;

import io.github.stepprflow.core.model.WorkflowMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Invokes callback methods (onSuccess, onFailure) using reflection.
 * Handles various method signatures: no args, single arg, or two args.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CallbackMethodInvoker {

    /** The payload deserializer. */
    private final PayloadDeserializer payloadDeserializer;

    /**
     * Invoke a callback method with deserialized payload.
     *
     * @param method  the method to invoke
     * @param handler the handler object
     * @param message the workflow message
     * @param error   the error (can be null for success callbacks)
     * @throws Exception if invocation fails
     */
    public void invoke(
            final Method method,
            final Object handler,
            final WorkflowMessage message,
            final Throwable error) throws Exception {
        Class<?>[] paramTypes = method.getParameterTypes();

        if (paramTypes.length == 0) {
            method.invoke(handler);
        } else if (paramTypes.length == 1) {
            invokeWithOneParam(method, handler, message, error, paramTypes[0], true);
        } else if (paramTypes.length == 2) {
            invokeWithTwoParams(method, handler, message, error, paramTypes, true);
        } else {
            log.warn("Callback method {} has unsupported parameter count: {} (invoke with deserialization)",
                     method.getName(), paramTypes.length);
        }
    }

    /**
     * Invoke a callback method using raw payload (no deserialization).
     * Useful for failure handlers that don't need type conversion.
     *
     * @param method  the method to invoke
     * @param handler the handler object
     * @param message the workflow message
     * @param error   the error (can be null for success callbacks)
     * @throws Exception if invocation fails
     */
    public void invokeRaw(
            final Method method,
            final Object handler,
            final WorkflowMessage message,
            final Throwable error) throws Exception {
        Class<?>[] paramTypes = method.getParameterTypes();

        if (paramTypes.length == 0) {
            method.invoke(handler);
        } else if (paramTypes.length == 1) {
            invokeWithOneParam(method, handler, message, error, paramTypes[0], false);
        } else if (paramTypes.length == 2) {
            invokeWithTwoParams(method, handler, message, error, paramTypes, false);
        } else {
            log.warn("Callback method {} has unsupported parameter count: {} (invokeRaw without deserialization)",
                     method.getName(), paramTypes.length);
        }
    }

    private void invokeWithOneParam(
            final Method method,
            final Object handler,
            final WorkflowMessage message,
            final Throwable error,
            final Class<?> paramType,
            final boolean deserialize) throws Exception {
        if (WorkflowMessage.class.isAssignableFrom(paramType)) {
            method.invoke(handler, message);
        } else if (Throwable.class.isAssignableFrom(paramType)) {
            // SF-27: a Throwable parameter always receives the error — which is
            // null on the success path. Previously the `error != null` guard made
            // a success callback declaring a Throwable fall through and get the
            // payload mis-injected (ClassCastException at invoke time).
            method.invoke(handler, error);
        } else {
            // Assume it's the payload type
            Object payload = deserialize
                    ? payloadDeserializer.deserialize(message)
                    : message.getPayload();
            method.invoke(handler, payload);
        }
    }

    private void invokeWithTwoParams(
            final Method method,
            final Object handler,
            final WorkflowMessage message,
            final Throwable error,
            final Class<?>[] paramTypes,
            final boolean deserialize) throws Exception {
        Class<?> firstParam = paramTypes[0];
        Class<?> secondParam = paramTypes[1];

        Object firstArg;
        if (WorkflowMessage.class.isAssignableFrom(firstParam)) {
            firstArg = message;
        } else {
            firstArg = deserialize
                    ? payloadDeserializer.deserialize(message)
                    : message.getPayload();
        }

        Object secondArg;
        if (Throwable.class.isAssignableFrom(secondParam)) {
            secondArg = error;
        } else {
            secondArg = null;
        }

        method.invoke(handler, firstArg, secondArg);
    }
}

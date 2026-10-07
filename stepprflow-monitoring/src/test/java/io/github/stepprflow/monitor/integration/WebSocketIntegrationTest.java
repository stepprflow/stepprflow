package io.github.stepprflow.monitor.integration;

import io.github.stepprflow.core.model.WorkflowStatus;
import io.github.stepprflow.monitor.model.WorkflowExecution;
import io.github.stepprflow.monitor.websocket.WorkflowBroadcaster;
import io.github.stepprflow.monitor.websocket.WorkflowWebSocketHandler.WorkflowUpdateDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * WebSocket integration tests.
 *
 * Tests WebSocket connections, subscriptions, and message broadcasting
 * for real-time workflow updates. Uses a synchronous test broadcaster
 * (defined in TestApplication) to avoid @Async timing issues.
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("WebSocket Integration Tests")
class WebSocketIntegrationTest {

    /** Basic-auth handshake headers matching the test-profile local user (SF-5). */
    private final WebSocketHttpHeaders handshakeHeaders = buildHandshakeHeaders();

    private static WebSocketHttpHeaders buildHandshakeHeaders() {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        String creds = Base64.getEncoder().encodeToString(
                "test-admin:test-password".getBytes(StandardCharsets.UTF_8));
        headers.add("Authorization", "Basic " + creds);
        return headers;
    }

    private static final long SUBSCRIPTION_WAIT_MS = 500;

    @Container
    static MongoDBContainer mongodb = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongodb::getReplicaSetUrl);
        registry.add("spring.data.mongodb.database", () -> "stepprflow-test");
        registry.add("spring.autoconfigure.exclude", () -> "de.flapdoodle.embed.mongo.spring.autoconfigure.EmbeddedMongoAutoConfiguration");
        registry.add("stepprflow.circuit-breaker.enabled", () -> false);
        registry.add("stepprflow.monitor.web-socket.enabled", () -> true);
        registry.add("stepprflow.monitor.web-socket.endpoint", () -> "/ws/workflow");
        registry.add("stepprflow.monitor.web-socket.topic-prefix", () -> "/topic/workflow");
        registry.add("stepprflow.monitor.retry-scheduler.enabled", () -> false);
        registry.add("stepprflow.monitor.outbox.enabled", () -> false);
    }

    @LocalServerPort
    private int port;

    @Autowired(required = false)
    private WorkflowBroadcaster broadcaster;

    private WebSocketStompClient stompClient;
    private String wsUrl;

    @BeforeEach
    void setUp() {
        var transports = List.<Transport>of(new WebSocketTransport(new StandardWebSocketClient()));
        var sockJsClient = new SockJsClient(transports);

        stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        wsUrl = "ws://localhost:" + port + "/ws/workflow";
    }

    @Nested
    @DisplayName("WebSocket connection")
    class ConnectionTests {

        @Test
        @DisplayName("Should connect to WebSocket endpoint")
        void shouldConnectToWebSocketEndpoint() throws Exception {
            var session = stompClient.connectAsync(wsUrl, handshakeHeaders, new TestStompSessionHandler())
                    .get(5, TimeUnit.SECONDS);

            assertThat(session).isNotNull();
            assertThat(session.isConnected()).isTrue();

            session.disconnect();
        }

        @Test
        @DisplayName("Should subscribe to updates topic")
        void shouldSubscribeToUpdatesTopic() throws Exception {
            var messageQueue = new LinkedBlockingQueue<WorkflowUpdateDTO>();

            var session = stompClient.connectAsync(wsUrl, handshakeHeaders, new TestStompSessionHandler())
                    .get(5, TimeUnit.SECONDS);

            session.subscribe("/topic/workflow/updates", new TestStompFrameHandler(messageQueue));

            assertThat(session.isConnected()).isTrue();

            session.disconnect();
        }
    }

    @Nested
    @DisplayName("Broadcast updates")
    class BroadcastTests {

        @Test
        @DisplayName("Should receive broadcast on general updates topic")
        void shouldReceiveBroadcastOnGeneralUpdatesTopic() throws Exception {
            assumeTrue(broadcaster != null, "WebSocketHandler not available");

            var messageQueue = new LinkedBlockingQueue<WorkflowUpdateDTO>();
            var session = stompClient.connectAsync(wsUrl, handshakeHeaders, new TestStompSessionHandler())
                    .get(5, TimeUnit.SECONDS);
            session.subscribe("/topic/workflow/updates", new TestStompFrameHandler(messageQueue));
            Thread.sleep(SUBSCRIPTION_WAIT_MS);

            var execution = createExecution("exec-broadcast-1", "order-workflow", WorkflowStatus.IN_PROGRESS);

            broadcaster.broadcastUpdate(execution);

            var received = messageQueue.poll(5, TimeUnit.SECONDS);

            assertThat(received).isNotNull();
            assertThat(received)
                    .extracting(
                            WorkflowUpdateDTO::getExecutionId,
                            WorkflowUpdateDTO::getTopic,
                            WorkflowUpdateDTO::getStatus
                    )
                    .containsExactly(
                            "exec-broadcast-1",
                            "order-workflow",
                            WorkflowStatus.IN_PROGRESS
                    );

            session.disconnect();
        }

        @Test
        @DisplayName("Should receive broadcast on topic-specific channel")
        void shouldReceiveBroadcastOnTopicSpecificChannel() throws Exception {
            assumeTrue(broadcaster != null, "WebSocketHandler not available");

            var messageQueue = new LinkedBlockingQueue<WorkflowUpdateDTO>();
            var session = stompClient.connectAsync(wsUrl, handshakeHeaders, new TestStompSessionHandler())
                    .get(5, TimeUnit.SECONDS);
            session.subscribe("/topic/workflow/payment-workflow", new TestStompFrameHandler(messageQueue));
            Thread.sleep(SUBSCRIPTION_WAIT_MS);

            var execution = createExecution("exec-topic-1", "payment-workflow", WorkflowStatus.COMPLETED);

            broadcaster.broadcastUpdate(execution);

            var received = messageQueue.poll(5, TimeUnit.SECONDS);

            assertThat(received).isNotNull();
            assertThat(received.getTopic()).isEqualTo("payment-workflow");
            assertThat(received.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);

            session.disconnect();
        }

        @Test
        @DisplayName("Should receive broadcast on execution-specific channel")
        void shouldReceiveBroadcastOnExecutionSpecificChannel() throws Exception {
            assumeTrue(broadcaster != null, "WebSocketHandler not available");

            var executionId = "exec-specific-" + UUID.randomUUID();
            var messageQueue = new LinkedBlockingQueue<WorkflowUpdateDTO>();
            var session = stompClient.connectAsync(wsUrl, handshakeHeaders, new TestStompSessionHandler())
                    .get(5, TimeUnit.SECONDS);
            session.subscribe("/topic/workflow/execution/" + executionId, new TestStompFrameHandler(messageQueue));
            Thread.sleep(SUBSCRIPTION_WAIT_MS);

            var execution = createExecution(executionId, "notification-workflow", WorkflowStatus.FAILED);

            broadcaster.broadcastUpdate(execution);

            var received = messageQueue.poll(5, TimeUnit.SECONDS);

            assertThat(received).isNotNull();
            assertThat(received.getExecutionId()).isEqualTo(executionId);
            assertThat(received.getStatus()).isEqualTo(WorkflowStatus.FAILED);

            session.disconnect();
        }

        @Test
        @DisplayName("Should not receive messages from different topic channel")
        void shouldNotReceiveMessagesFromDifferentTopicChannel() throws Exception {
            assumeTrue(broadcaster != null, "WebSocketHandler not available");

            var messageQueue = new LinkedBlockingQueue<WorkflowUpdateDTO>();
            var session = stompClient.connectAsync(wsUrl, handshakeHeaders, new TestStompSessionHandler())
                    .get(5, TimeUnit.SECONDS);
            session.subscribe("/topic/workflow/order-workflow", new TestStompFrameHandler(messageQueue));
            Thread.sleep(SUBSCRIPTION_WAIT_MS);

            var execution = createExecution("exec-other-1", "payment-workflow", WorkflowStatus.PENDING);

            broadcaster.broadcastUpdate(execution);

            var received = messageQueue.poll(2, TimeUnit.SECONDS);
            assertThat(received).isNull();

            session.disconnect();
        }
    }

    @Nested
    @DisplayName("Multiple subscribers")
    class MultipleSubscribersTests {

        @Test
        @DisplayName("Should broadcast to multiple subscribers")
        void shouldBroadcastToMultipleSubscribers() throws Exception {
            assumeTrue(broadcaster != null, "WebSocketHandler not available");

            var messageQueue1 = new LinkedBlockingQueue<WorkflowUpdateDTO>();
            var messageQueue2 = new LinkedBlockingQueue<WorkflowUpdateDTO>();

            var session1 = stompClient.connectAsync(wsUrl, handshakeHeaders, new TestStompSessionHandler())
                    .get(5, TimeUnit.SECONDS);
            var session2 = stompClient.connectAsync(wsUrl, handshakeHeaders, new TestStompSessionHandler())
                    .get(5, TimeUnit.SECONDS);
            session1.subscribe("/topic/workflow/updates", new TestStompFrameHandler(messageQueue1));
            session2.subscribe("/topic/workflow/updates", new TestStompFrameHandler(messageQueue2));
            Thread.sleep(SUBSCRIPTION_WAIT_MS);

            var execution = createExecution("exec-multi-1", "shared-workflow", WorkflowStatus.IN_PROGRESS);

            broadcaster.broadcastUpdate(execution);

            var received1 = messageQueue1.poll(5, TimeUnit.SECONDS);
            var received2 = messageQueue2.poll(5, TimeUnit.SECONDS);

            assertThat(received1).isNotNull();
            assertThat(received2).isNotNull();

            assertThat(List.of(received1, received2))
                    .extracting(WorkflowUpdateDTO::getExecutionId)
                    .containsOnly("exec-multi-1");

            session1.disconnect();
            session2.disconnect();
        }

        @Test
        @DisplayName("Should receive on multiple subscriptions from same session")
        void shouldReceiveOnMultipleSubscriptionsFromSameSession() throws Exception {
            assumeTrue(broadcaster != null, "WebSocketHandler not available");

            var generalQueue = new LinkedBlockingQueue<WorkflowUpdateDTO>();
            var topicQueue = new LinkedBlockingQueue<WorkflowUpdateDTO>();

            var session = stompClient.connectAsync(wsUrl, handshakeHeaders, new TestStompSessionHandler())
                    .get(5, TimeUnit.SECONDS);
            session.subscribe("/topic/workflow/updates", new TestStompFrameHandler(generalQueue));
            session.subscribe("/topic/workflow/order-workflow", new TestStompFrameHandler(topicQueue));
            Thread.sleep(SUBSCRIPTION_WAIT_MS);

            var execution = createExecution("exec-dual-1", "order-workflow", WorkflowStatus.COMPLETED);

            broadcaster.broadcastUpdate(execution);

            var generalReceived = generalQueue.poll(5, TimeUnit.SECONDS);
            var topicReceived = topicQueue.poll(5, TimeUnit.SECONDS);

            assertThat(generalReceived).isNotNull();
            assertThat(topicReceived).isNotNull();

            assertThat(generalReceived.getExecutionId()).isEqualTo(topicReceived.getExecutionId());

            session.disconnect();
        }
    }

    @Nested
    @DisplayName("Workflow status transitions")
    class StatusTransitionTests {

        @Test
        @DisplayName("Should receive all status transitions on general updates topic")
        void shouldReceiveAllStatusTransitions() throws Exception {
            assumeTrue(broadcaster != null, "WebSocketHandler not available");

            var messageQueue = new LinkedBlockingQueue<WorkflowUpdateDTO>();
            var session = stompClient.connectAsync(wsUrl, handshakeHeaders, new TestStompSessionHandler())
                    .get(5, TimeUnit.SECONDS);
            session.subscribe("/topic/workflow/updates", new TestStompFrameHandler(messageQueue));
            Thread.sleep(SUBSCRIPTION_WAIT_MS);

            var executionId = "exec-transition-" + UUID.randomUUID();

            var statuses = List.of(
                    WorkflowStatus.PENDING,
                    WorkflowStatus.IN_PROGRESS,
                    WorkflowStatus.COMPLETED
            );

            for (WorkflowStatus status : statuses) {
                broadcaster.broadcastUpdate(createExecution(executionId, "progressive-workflow", status));
            }

            var receivedStatuses = new java.util.ArrayList<WorkflowStatus>();
            for (int i = 0; i < 3; i++) {
                var received = messageQueue.poll(5, TimeUnit.SECONDS);
                if (received != null && executionId.equals(received.getExecutionId())) {
                    receivedStatuses.add(received.getStatus());
                }
            }

            assertThat(receivedStatuses)
                    .containsExactlyInAnyOrder(
                            WorkflowStatus.PENDING,
                            WorkflowStatus.IN_PROGRESS,
                            WorkflowStatus.COMPLETED
                    );

            session.disconnect();
        }
    }

    // Helper methods
    private WorkflowExecution createExecution(String executionId, String topic, WorkflowStatus status) {
        return WorkflowExecution.builder()
                .executionId(executionId)
                .correlationId(UUID.randomUUID().toString())
                .topic(topic)
                .status(status)
                .currentStep(1)
                .totalSteps(3)
                .createdAt(Instant.now())
                .build();
    }

    private static class TestStompSessionHandler extends StompSessionHandlerAdapter {
        @Override
        public void handleException(StompSession session, StompCommand command,
                                    StompHeaders headers, byte[] payload, Throwable exception) {
            throw new RuntimeException("WebSocket error", exception);
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            throw new RuntimeException("Transport error", exception);
        }
    }

    private static class TestStompFrameHandler implements StompFrameHandler {
        private final BlockingQueue<WorkflowUpdateDTO> messageQueue;

        TestStompFrameHandler(BlockingQueue<WorkflowUpdateDTO> messageQueue) {
            this.messageQueue = messageQueue;
        }

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return WorkflowUpdateDTO.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            messageQueue.offer((WorkflowUpdateDTO) payload);
        }
    }
}

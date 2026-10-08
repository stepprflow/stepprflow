package io.github.stepprflow.core.registration;

import io.github.stepprflow.core.broker.MessageBroker;
import io.github.stepprflow.core.model.StepDefinition;
import io.github.stepprflow.core.model.WorkflowDefinition;
import io.github.stepprflow.core.model.WorkflowMessage;
import io.github.stepprflow.core.model.WorkflowRegistrationRequest;
import io.github.stepprflow.core.model.WorkflowStatus;
import io.github.stepprflow.core.service.WorkflowRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WorkflowRegistrationClient Tests")
class WorkflowRegistrationClientTest {

    @Nested
    @DisplayName("Unit tests - toWorkflowInfo")
    @ExtendWith(MockitoExtension.class)
    class ToWorkflowInfoTests {

        @Mock
        private WorkflowRegistry workflowRegistry;
        @Mock
        private MessageBroker messageBroker;

        private WorkflowRegistrationClient client;

        @BeforeEach
        void setUp() {
            RegistrationProperties properties = new RegistrationProperties();
            client = new WorkflowRegistrationClient(
                    properties, workflowRegistry, messageBroker,
                    "test-app", 8080);
        }

        @Test
        @DisplayName("Should convert WorkflowDefinition to WorkflowInfo")
        void shouldConvertDefinitionToWorkflowInfo() {
            WorkflowDefinition definition = WorkflowDefinition.builder()
                    .topic("order-workflow")
                    .description("Order processing")
                    .partitions(3)
                    .replication((short) 2)
                    .timeout(Duration.ofMinutes(5))
                    .steps(List.of(
                            StepDefinition.builder()
                                    .id(1)
                                    .label("Validate")
                                    .description("Validate order")
                                    .skippable(false)
                                    .continueOnFailure(false)
                                    .timeout(Duration.ofSeconds(30))
                                    .build(),
                            StepDefinition.builder()
                                    .id(2)
                                    .label("Process")
                                    .description("Process payment")
                                    .skippable(true)
                                    .continueOnFailure(true)
                                    .build()
                    ))
                    .build();

            WorkflowRegistrationRequest.WorkflowInfo info =
                    client.toWorkflowInfo(definition);

            assertThat(info.getTopic()).isEqualTo("order-workflow");
            assertThat(info.getDescription()).isEqualTo("Order processing");
            assertThat(info.getPartitions()).isEqualTo(3);
            assertThat(info.getReplication()).isEqualTo((short) 2);
            assertThat(info.getTimeoutMs()).isEqualTo(300000L);
            assertThat(info.getSteps()).hasSize(2);

            WorkflowRegistrationRequest.StepInfo step1 = info.getSteps().get(0);
            assertThat(step1.getId()).isEqualTo(1);
            assertThat(step1.getLabel()).isEqualTo("Validate");
            assertThat(step1.getDescription()).isEqualTo("Validate order");
            assertThat(step1.isSkippable()).isFalse();
            assertThat(step1.isContinueOnFailure()).isFalse();
            assertThat(step1.getTimeoutMs()).isEqualTo(30000L);

            WorkflowRegistrationRequest.StepInfo step2 = info.getSteps().get(1);
            assertThat(step2.getId()).isEqualTo(2);
            assertThat(step2.isSkippable()).isTrue();
            assertThat(step2.isContinueOnFailure()).isTrue();
            assertThat(step2.getTimeoutMs()).isNull();
        }

        @Test
        @DisplayName("Should handle null timeout on definition")
        void shouldHandleNullTimeout() {
            WorkflowDefinition definition = WorkflowDefinition.builder()
                    .topic("simple-workflow")
                    .description("Simple")
                    .steps(List.of(
                            StepDefinition.builder()
                                    .id(1)
                                    .label("Step 1")
                                    .build()
                    ))
                    .build();

            WorkflowRegistrationRequest.WorkflowInfo info =
                    client.toWorkflowInfo(definition);

            assertThat(info.getTimeoutMs()).isNull();
        }
    }

    @Nested
    @DisplayName("Unit tests - init and lifecycle")
    @ExtendWith(MockitoExtension.class)
    class LifecycleTests {

        @Mock
        private WorkflowRegistry workflowRegistry;
        @Mock
        private MessageBroker messageBroker;

        @Test
        @DisplayName("Should not register when enabled is false")
        void shouldNotRegisterWhenDisabled() {
            RegistrationProperties properties = new RegistrationProperties();
            properties.setEnabled(false);

            WorkflowRegistrationClient client = new WorkflowRegistrationClient(
                    properties, workflowRegistry, messageBroker,
                    "test-app", 8080);

            client.init();
            client.shutdown();

            verify(messageBroker, never()).send(any(), any());
        }

        @Test
        @DisplayName("Should skip registration when no definitions exist")
        void shouldSkipRegistrationWhenNoDefinitions() {
            RegistrationProperties properties = new RegistrationProperties();

            when(workflowRegistry.getAllDefinitions())
                    .thenReturn(Collections.emptyList());

            WorkflowRegistrationClient client = new WorkflowRegistrationClient(
                    properties, workflowRegistry, messageBroker,
                    "test-app", 8080);

            client.registerWorkflows();

            verify(messageBroker, never()).send(any(), any());
        }

        @Test
        @DisplayName("Should not send heartbeat when not registered")
        void shouldNotSendHeartbeatWhenNotRegistered() {
            RegistrationProperties properties = new RegistrationProperties();

            WorkflowRegistrationClient client = new WorkflowRegistrationClient(
                    properties, workflowRegistry, messageBroker,
                    "test-app", 8080);

            client.heartbeat();

            verify(messageBroker, never()).send(any(), any());
        }

        @Test
        @DisplayName("Should ignore non-ANNOUNCE registration messages (no re-register loop)")
        void shouldIgnoreNonAnnounceMessages() {
            RegistrationProperties properties = new RegistrationProperties();
            WorkflowRegistrationClient client = new WorkflowRegistrationClient(
                    properties, workflowRegistry, messageBroker,
                    "test-app", 8080);

            for (String action : List.of(
                    WorkflowRegistrationRequest.ACTION_REGISTER,
                    WorkflowRegistrationRequest.ACTION_HEARTBEAT,
                    WorkflowRegistrationRequest.ACTION_DEREGISTER)) {
                client.onRegistrationMessage(WorkflowMessage.builder()
                        .topic(WorkflowRegistrationRequest.REGISTRATION_TOPIC)
                        .metadata(Map.of(WorkflowRegistrationRequest.METADATA_ACTION, action))
                        .build());
            }
            client.onRegistrationMessage(null);

            verify(messageBroker, never()).send(any(), any());
        }
    }

    @Nested
    @DisplayName("Broker integration tests")
    @ExtendWith(MockitoExtension.class)
    class BrokerIntegrationTests {

        @Mock
        private WorkflowRegistry workflowRegistry;
        @Mock
        private MessageBroker messageBroker;

        private WorkflowRegistrationClient client;

        @BeforeEach
        void setUp() {
            RegistrationProperties properties = new RegistrationProperties();
            when(messageBroker.getBrokerType()).thenReturn("kafka");
            client = new WorkflowRegistrationClient(
                    properties, workflowRegistry, messageBroker,
                    "test-app", 8080);
        }

        @Test
        @DisplayName("Should send REGISTER message via broker")
        void shouldSendRegisterMessage() {
            when(workflowRegistry.getAllDefinitions()).thenReturn(List.of(
                    WorkflowDefinition.builder()
                            .topic("order-workflow")
                            .description("Order processing")
                            .partitions(3)
                            .replication((short) 1)
                            .steps(List.of(
                                    StepDefinition.builder()
                                            .id(1)
                                            .label("Validate")
                                            .build()
                            ))
                            .build()
            ));

            client.registerWorkflows();

            ArgumentCaptor<WorkflowMessage> captor = ArgumentCaptor.forClass(WorkflowMessage.class);
            verify(messageBroker).send(
                    eq(WorkflowRegistrationRequest.REGISTRATION_TOPIC), captor.capture());

            WorkflowMessage sent = captor.getValue();
            assertThat(sent.getTopic()).isEqualTo(WorkflowRegistrationRequest.REGISTRATION_TOPIC);
            assertThat(sent.getServiceName()).isEqualTo("test-app");
            assertThat(sent.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(sent.getPayload()).isNotNull();

            Map<String, Object> metadata = sent.getMetadata();
            assertThat(metadata.get(WorkflowRegistrationRequest.METADATA_ACTION))
                    .isEqualTo(WorkflowRegistrationRequest.ACTION_REGISTER);
            assertThat(metadata.get(WorkflowRegistrationRequest.METADATA_INSTANCE_ID))
                    .isNotNull();
        }

        @Test
        @DisplayName("Should send HEARTBEAT message after registration")
        void shouldSendHeartbeatAfterRegistration() {
            when(workflowRegistry.getAllDefinitions()).thenReturn(List.of(
                    WorkflowDefinition.builder()
                            .topic("test-workflow")
                            .description("Test")
                            .steps(List.of(
                                    StepDefinition.builder()
                                            .id(1)
                                            .label("Step 1")
                                            .build()
                            ))
                            .build()
            ));

            client.registerWorkflows();
            client.heartbeat();

            ArgumentCaptor<WorkflowMessage> captor = ArgumentCaptor.forClass(WorkflowMessage.class);
            verify(messageBroker, org.mockito.Mockito.times(2)).send(
                    eq(WorkflowRegistrationRequest.REGISTRATION_TOPIC), captor.capture());

            WorkflowMessage heartbeat = captor.getAllValues().get(1);
            assertThat(heartbeat.getPayload()).isNull();
            assertThat(heartbeat.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(heartbeat.getMetadata().get(WorkflowRegistrationRequest.METADATA_ACTION))
                    .isEqualTo(WorkflowRegistrationRequest.ACTION_HEARTBEAT);
        }

        @Test
        @DisplayName("Should send DEREGISTER message on shutdown")
        void shouldSendDeregisterOnShutdown() {
            when(workflowRegistry.getAllDefinitions()).thenReturn(List.of(
                    WorkflowDefinition.builder()
                            .topic("test-workflow")
                            .description("Test")
                            .steps(List.of(
                                    StepDefinition.builder()
                                            .id(1)
                                            .label("Step 1")
                                            .build()
                            ))
                            .build()
            ));

            client.init();
            client.registerWorkflows();
            client.shutdown();

            ArgumentCaptor<WorkflowMessage> captor = ArgumentCaptor.forClass(WorkflowMessage.class);
            verify(messageBroker).sendSync(
                    eq(WorkflowRegistrationRequest.REGISTRATION_TOPIC), captor.capture());

            WorkflowMessage deregister = captor.getValue();
            assertThat(deregister.getMetadata().get(WorkflowRegistrationRequest.METADATA_ACTION))
                    .isEqualTo(WorkflowRegistrationRequest.ACTION_DEREGISTER);
            assertThat(deregister.getServiceName()).isEqualTo("test-app");
        }

        @Test
        @DisplayName("Should handle registration failure gracefully")
        void shouldHandleRegistrationFailureGracefully() {
            when(workflowRegistry.getAllDefinitions()).thenReturn(List.of(
                    WorkflowDefinition.builder()
                            .topic("test-workflow")
                            .description("Test")
                            .steps(List.of(
                                    StepDefinition.builder()
                                            .id(1)
                                            .label("Step 1")
                                            .build()
                            ))
                            .build()
            ));

            org.mockito.Mockito.doThrow(new RuntimeException("Broker down"))
                    .when(messageBroker).send(any(), any());

            // init() uses getBrokerType() for logging
            client.init();

            // Should not throw
            client.registerWorkflows();

            // Heartbeat should be no-op since registration failed
            client.heartbeat();

            client.shutdown();
        }

        @Test
        @DisplayName("Should re-register (REGISTER) when a monitor ANNOUNCE is received")
        void shouldReRegisterOnAnnounce() {
            when(workflowRegistry.getAllDefinitions()).thenReturn(List.of(
                    WorkflowDefinition.builder()
                            .topic("order-workflow")
                            .description("Order processing")
                            .steps(List.of(
                                    StepDefinition.builder().id(1).label("Validate").build()))
                            .build()
            ));

            WorkflowMessage announce = WorkflowMessage.builder()
                    .topic(WorkflowRegistrationRequest.REGISTRATION_TOPIC)
                    .metadata(Map.of(WorkflowRegistrationRequest.METADATA_ACTION,
                            WorkflowRegistrationRequest.ACTION_ANNOUNCE))
                    .build();

            client.onRegistrationMessage(announce);

            ArgumentCaptor<WorkflowMessage> captor = ArgumentCaptor.forClass(WorkflowMessage.class);
            verify(messageBroker).send(
                    eq(WorkflowRegistrationRequest.REGISTRATION_TOPIC), captor.capture());
            assertThat(captor.getValue().getMetadata()
                    .get(WorkflowRegistrationRequest.METADATA_ACTION))
                    .isEqualTo(WorkflowRegistrationRequest.ACTION_REGISTER);
            assertThat(captor.getValue().getPayload())
                    .as("re-registration must carry the full workflow catalogue (steps)")
                    .isNotNull();
        }
    }
}

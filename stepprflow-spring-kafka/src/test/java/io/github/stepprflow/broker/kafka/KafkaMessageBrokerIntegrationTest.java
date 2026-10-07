package io.github.stepprflow.broker.kafka;

import io.github.stepprflow.core.model.WorkflowMessage;
import io.github.stepprflow.core.model.WorkflowStatus;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for KafkaMessageBroker using Testcontainers.
 */
@SpringBootTest(classes = KafkaMessageBrokerIntegrationTest.TestConfig.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
@Testcontainers
class KafkaMessageBrokerIntegrationTest {

    private static final String TEST_TOPIC = "test-workflow-topic";

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
    );

    private static AdminClient adminClient;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("test.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @BeforeAll
    static void createTopics() throws Exception {
        Map<String, Object> adminProps = new HashMap<>();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        adminClient = AdminClient.create(adminProps);

        NewTopic topic = new NewTopic(TEST_TOPIC, 1, (short) 1);
        adminClient.createTopics(Collections.singletonList(topic)).all().get(30, TimeUnit.SECONDS);
    }

    @AfterAll
    static void cleanup() {
        if (adminClient != null) {
            adminClient.close();
        }
    }

    @Autowired
    private KafkaMessageBroker messageBroker;

    @Test
    void send_shouldDeliverMessageToKafkaTopic() {
        // Given
        WorkflowMessage message = createTestMessage();
        KafkaConsumer<String, WorkflowMessage> consumer = createConsumer();

        try {
            // Subscribe and wait for partition assignment
            consumer.subscribe(Collections.singletonList(TEST_TOPIC));
            waitForAssignment(consumer);

            // When
            messageBroker.send(TEST_TOPIC, message);

            // Then
            ConsumerRecord<String, WorkflowMessage> record = pollForRecord(consumer, message.getExecutionId());
            assertThat(record).isNotNull();
            assertThat(record.key()).isEqualTo(message.getExecutionId());
            assertThat(record.value().getExecutionId()).isEqualTo(message.getExecutionId());
            assertThat(record.value().getTopic()).isEqualTo(message.getTopic());
            assertThat(record.value().getCurrentStep()).isEqualTo(message.getCurrentStep());
        } finally {
            consumer.close();
        }
    }

    @Test
    void sendSync_shouldDeliverMessageSynchronously() {
        // Given
        WorkflowMessage message = createTestMessage();

        try (KafkaConsumer<String, WorkflowMessage> consumer = createConsumer()) {
            consumer.subscribe(Collections.singletonList(TEST_TOPIC));
            waitForAssignment(consumer);

            // When
            messageBroker.sendSync(TEST_TOPIC, message);

            // Then
            ConsumerRecord<String, WorkflowMessage> record = pollForRecord(consumer, message.getExecutionId());
            assertThat(record).isNotNull();
            assertThat(record.value().getExecutionId()).isEqualTo(message.getExecutionId());
        }
    }

    @Test
    void sendAsync_shouldDeliverMessageAsynchronously() throws Exception {
        // Given
        WorkflowMessage message = createTestMessage();
        KafkaConsumer<String, WorkflowMessage> consumer = createConsumer();

        try {
            consumer.subscribe(Collections.singletonList(TEST_TOPIC));
            waitForAssignment(consumer);

            // When
            messageBroker.sendAsync(TEST_TOPIC, message).get(10, TimeUnit.SECONDS);

            // Then
            ConsumerRecord<String, WorkflowMessage> record = pollForRecord(consumer, message.getExecutionId());
            assertThat(record).isNotNull();
            assertThat(record.value().getExecutionId()).isEqualTo(message.getExecutionId());
        } finally {
            consumer.close();
        }
    }

    @Test
    void getBrokerType_shouldReturnKafka() {
        assertThat(messageBroker.getBrokerType()).isEqualTo("kafka");
    }

    @Test
    void isAvailable_shouldReturnTrueWhenConnected() {
        assertThat(messageBroker.isAvailable()).isTrue();
    }

    @Test
    void send_shouldMaintainMessageOrderFIFO() {
        // Given - send multiple messages with the same key (executionId)
        int messageCount = 5;
        String sharedExecutionId = "fifo-test-" + UUID.randomUUID();
        String[] correlationIds = new String[messageCount];

        try (KafkaConsumer<String, WorkflowMessage> consumer = createConsumer()) {
            consumer.subscribe(Collections.singletonList(TEST_TOPIC));
            waitForAssignment(consumer);

            // When - send messages with same key to ensure same partition
            for (int i = 0; i < messageCount; i++) {
                correlationIds[i] = "correlation-" + i;
                WorkflowMessage message = WorkflowMessage.builder()
                        .executionId(sharedExecutionId)
                        .correlationId(correlationIds[i])
                        .topic(TEST_TOPIC)
                        .currentStep(i + 1)
                        .totalSteps(messageCount)
                        .status(WorkflowStatus.IN_PROGRESS)
                        .payload(Map.of("order", i))
                        .build();
                messageBroker.sendSync(TEST_TOPIC, message);
            }

            // Then - messages should be received in FIFO order (same partition = ordered)
            List<WorkflowMessage> receivedMessages = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 30_000;
            while (receivedMessages.size() < messageCount && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, WorkflowMessage> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, WorkflowMessage> record : records) {
                    if (record.value() != null && sharedExecutionId.equals(record.value().getExecutionId())) {
                        receivedMessages.add(record.value());
                    }
                }
            }

            assertThat(receivedMessages).hasSize(messageCount);
            for (int i = 0; i < messageCount; i++) {
                assertThat(receivedMessages.get(i).getCorrelationId()).isEqualTo(correlationIds[i]);
                assertThat(receivedMessages.get(i).getCurrentStep()).isEqualTo(i + 1);
            }
        }
    }

    @Test
    void sendMultipleMessages_shouldAllBeDelivered() {
        // Given
        int messageCount = 10;
        List<String> sentIds = new ArrayList<>();

        try (KafkaConsumer<String, WorkflowMessage> consumer = createConsumer()) {
            consumer.subscribe(Collections.singletonList(TEST_TOPIC));
            waitForAssignment(consumer);

            // When - send multiple messages with unique execution IDs
            for (int i = 0; i < messageCount; i++) {
                WorkflowMessage message = createTestMessage();
                sentIds.add(message.getExecutionId());
                messageBroker.sendSync(TEST_TOPIC, message);
            }

            // Then - all messages should be received
            List<String> receivedIds = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 30_000;
            while (receivedIds.size() < messageCount && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, WorkflowMessage> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, WorkflowMessage> record : records) {
                    if (record.value() != null && sentIds.contains(record.value().getExecutionId())) {
                        receivedIds.add(record.value().getExecutionId());
                    }
                }
            }

            assertThat(receivedIds).hasSize(messageCount);
            assertThat(receivedIds).containsExactlyInAnyOrderElementsOf(sentIds);
        }
    }

    @Test
    void send_shouldUseExecutionIdAsPartitionKey() {
        // Given - messages with same executionId should go to same partition
        String sharedExecutionId = "partition-test-" + UUID.randomUUID();
        int messageCount = 3;

        try (KafkaConsumer<String, WorkflowMessage> consumer = createConsumer()) {
            consumer.subscribe(Collections.singletonList(TEST_TOPIC));
            waitForAssignment(consumer);

            // When - send multiple messages with same executionId
            for (int i = 0; i < messageCount; i++) {
                WorkflowMessage message = WorkflowMessage.builder()
                        .executionId(sharedExecutionId)
                        .correlationId(UUID.randomUUID().toString())
                        .topic(TEST_TOPIC)
                        .currentStep(i + 1)
                        .totalSteps(messageCount)
                        .status(WorkflowStatus.IN_PROGRESS)
                        .payload(Map.of("index", i))
                        .build();
                messageBroker.sendSync(TEST_TOPIC, message);
            }

            // Then - all messages should be on the same partition
            List<Integer> partitions = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 30_000;
            int receivedCount = 0;
            while (receivedCount < messageCount && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, WorkflowMessage> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, WorkflowMessage> record : records) {
                    if (record.value() != null && sharedExecutionId.equals(record.value().getExecutionId())) {
                        partitions.add(record.partition());
                        receivedCount++;
                    }
                }
            }

            assertThat(partitions).hasSize(messageCount);
            // All messages should be on the same partition
            assertThat(partitions.stream().distinct()).hasSize(1);
        }
    }

    private void waitForAssignment(KafkaConsumer<?, ?> consumer) {
        await().atMost(30, TimeUnit.SECONDS).until(() -> {
            consumer.poll(Duration.ofMillis(100));
            Set<TopicPartition> assignment = consumer.assignment();
            return !assignment.isEmpty();
        });
    }

    private ConsumerRecord<String, WorkflowMessage> pollForRecord(
            KafkaConsumer<String, WorkflowMessage> consumer, String expectedExecutionId) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, WorkflowMessage> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, WorkflowMessage> record : records) {
                if (record.value() != null &&
                    expectedExecutionId.equals(record.value().getExecutionId())) {
                    return record;
                }
            }
        }
        return null;
    }

    private KafkaConsumer<String, WorkflowMessage> createConsumer() {
        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-group-" + UUID.randomUUID());
        // 'earliest', not 'latest': with a brand-new group, 'latest' resolves the
        // read position lazily on the first poll after assignment, so messages
        // produced in the window right after waitForAssignment() can land before
        // the position and be missed — the root cause of the flaky
        // sendMultipleMessages test. Reading from the beginning and filtering by
        // the per-test unique executionId makes delivery deterministic.
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        consumerProps.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        consumerProps.put(JsonDeserializer.VALUE_DEFAULT_TYPE, WorkflowMessage.class.getName());

        return new KafkaConsumer<>(consumerProps);
    }

    private WorkflowMessage createTestMessage() {
        return WorkflowMessage.builder()
                .executionId(UUID.randomUUID().toString())
                .correlationId(UUID.randomUUID().toString())
                .topic(TEST_TOPIC)
                .currentStep(1)
                .totalSteps(3)
                .status(WorkflowStatus.IN_PROGRESS)
                .payload(Map.of("key", "value"))
                .build();
    }

    @Configuration
    static class TestConfig {

        @Bean
        public ProducerFactory<String, WorkflowMessage> producerFactory(
                @Value("${test.kafka.bootstrap-servers}") String bootstrapServers) {
            Map<String, Object> configProps = new HashMap<>();
            configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
            return new DefaultKafkaProducerFactory<>(configProps);
        }

        @Bean
        public KafkaTemplate<String, WorkflowMessage> kafkaTemplate(
                ProducerFactory<String, WorkflowMessage> producerFactory) {
            return new KafkaTemplate<>(producerFactory);
        }

        @Bean
        public KafkaMessageBroker kafkaMessageBroker(
                KafkaTemplate<String, WorkflowMessage> kafkaTemplate) {
            return new KafkaMessageBroker(kafkaTemplate);
        }
    }
}

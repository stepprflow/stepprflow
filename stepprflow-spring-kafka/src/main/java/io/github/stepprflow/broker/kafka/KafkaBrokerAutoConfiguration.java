package io.github.stepprflow.broker.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stepprflow.core.StepprFlowProperties;
import io.github.stepprflow.core.broker.MessageBroker;
import io.github.stepprflow.core.security.TrustedPackagesValidator;
import io.github.stepprflow.core.model.WorkflowMessage;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import io.github.stepprflow.core.service.StepExecutor;
import io.github.stepprflow.core.service.WorkflowRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Auto-configuration for Kafka message broker.
 * Activated when stepprflow.broker=kafka (default).
 */
@AutoConfiguration
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty(name = "stepprflow.broker", havingValue = "kafka", matchIfMissing = true)
@EnableConfigurationProperties(StepprFlowProperties.class)
public class KafkaBrokerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ProducerFactory<String, WorkflowMessage> workflowProducerFactory(
            StepprFlowProperties properties, ObjectMapper objectMapper) {

        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, properties.getKafka().getProducer().getAcks());
        config.put(ProducerConfig.RETRIES_CONFIG, properties.getKafka().getProducer().getRetries());
        config.put(ProducerConfig.BATCH_SIZE_CONFIG, properties.getKafka().getProducer().getBatchSize());
        config.put(ProducerConfig.LINGER_MS_CONFIG, properties.getKafka().getProducer().getLingerMs());
        // Performance optimizations
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        config.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 67108864); // 64MB buffer

        DefaultKafkaProducerFactory<String, WorkflowMessage> factory =
                new DefaultKafkaProducerFactory<>(config);

        JsonSerializer<WorkflowMessage> serializer = new JsonSerializer<>(objectMapper);
        factory.setValueSerializer(serializer);

        return factory;
    }

    @Bean
    @ConditionalOnMissingBean
    public KafkaTemplate<String, WorkflowMessage> workflowKafkaTemplate(
            ProducerFactory<String, WorkflowMessage> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConsumerFactory<String, WorkflowMessage> workflowConsumerFactory(
            StepprFlowProperties properties, ObjectMapper objectMapper) {

        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, properties.getKafka().getConsumer().getAutoOffsetReset());
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // Performance optimizations - batch fetching
        config.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 10240); // 10KB min fetch
        config.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500); // Wait max 500ms
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500); // More records per poll
        // Topic discovery - refresh metadata every 30 seconds for topicPattern matching
        config.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, 30000); // 30 seconds

        String groupId = properties.getKafka().getConsumer().getGroupId();
        if (groupId != null && !groupId.isEmpty()) {
            config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        }

        JsonDeserializer<WorkflowMessage> deserializer = new JsonDeserializer<>(WorkflowMessage.class, objectMapper);

        // SECURITY: Validate and apply trusted packages configuration
        // Never use wildcard (*) - it enables Remote Code Execution attacks
        List<String> trustedPackages = properties.getKafka().getTrustedPackages();
        TrustedPackagesValidator.validate(trustedPackages);
        deserializer.addTrustedPackages(trustedPackages.toArray(new String[0]));

        deserializer.setUseTypeHeaders(false);

        return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), deserializer);
    }

    @Bean
    @ConditionalOnMissingBean(name = "workflowKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, WorkflowMessage> workflowKafkaListenerContainerFactory(
            ConsumerFactory<String, WorkflowMessage> consumerFactory,
            StepprFlowProperties properties) {

        ConcurrentKafkaListenerContainerFactory<String, WorkflowMessage> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(properties.getKafka().getConsumer().getConcurrency());
        factory.getContainerProperties().setAckMode(
                org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // SF-4: on an un-acked (failed) record, seek back and redeliver with a
        // bounded backoff, retrying indefinitely until processing succeeds.
        // Infra failures (e.g. broker unreachable so a confirmed send threw)
        // must not be skipped — the record is acked only on success, so no
        // message is silently lost. Poison messages do not loop here: they are
        // routed to the DLQ inside StepExecutor, after which the record is acked.
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                new FixedBackOff(5000L, FixedBackOff.UNLIMITED_ATTEMPTS)));

        return factory;
    }

    @Bean
    @ConditionalOnMissingBean
    public KafkaAdmin kafkaAdmin(StepprFlowProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        return new KafkaAdmin(config);
    }

    @Bean
    @ConditionalOnMissingBean(MessageBroker.class)
    public MessageBroker messageBroker(KafkaTemplate<String, WorkflowMessage> kafkaTemplate) {
        return new KafkaMessageBroker(kafkaTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "stepprflow.workflow.executor.enabled", havingValue = "true", matchIfMissing = true)
    public KafkaMessageListener kafkaMessageListener(
            StepExecutor stepExecutor,
            WorkflowRegistry workflowRegistry,
            org.springframework.context.ApplicationEventPublisher eventPublisher) {
        return new KafkaMessageListener(stepExecutor, workflowRegistry, eventPublisher);
    }
}

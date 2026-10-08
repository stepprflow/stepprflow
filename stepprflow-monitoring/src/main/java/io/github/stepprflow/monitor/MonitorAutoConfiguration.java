package io.github.stepprflow.monitor;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.github.stepprflow.monitor.config.OpenApiConfig;
import io.github.stepprflow.monitor.config.WebSocketConfig;
import io.github.stepprflow.monitor.controller.CircuitBreakerController;
import io.github.stepprflow.monitor.controller.GlobalExceptionHandler;
import io.github.stepprflow.monitor.controller.HealthController;
import io.github.stepprflow.monitor.controller.OutboxController;
import io.github.stepprflow.monitor.controller.RegistryController;
import io.github.stepprflow.monitor.controller.RetentionController;
import io.github.stepprflow.monitor.controller.WorkflowController;
import io.github.stepprflow.monitor.outbox.OutboxMessageRepository;
import io.github.stepprflow.monitor.repository.WorkflowExecutionRepository;
import io.github.stepprflow.monitor.outbox.OutboxRelayService;
import io.github.stepprflow.monitor.outbox.OutboxService;
import io.github.stepprflow.monitor.service.ExecutionPersistenceService;
import io.github.stepprflow.monitor.service.PayloadManagementService;
import io.github.stepprflow.monitor.service.RetentionService;
import io.github.stepprflow.monitor.service.RetrySchedulerService;
import io.github.stepprflow.monitor.service.WorkflowCommandService;
import io.github.stepprflow.monitor.service.WorkflowQueryService;
import io.github.stepprflow.monitor.service.WorkflowRegistryService;
import io.github.stepprflow.monitor.util.WorkflowMessageFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Auto-configuration for StepprFlow Monitor module.
 * Runs before Spring Boot's MongoDB auto-configuration to use StepprFlow properties.
 */
@AutoConfiguration(
        before = {MongoAutoConfiguration.class, MongoDataAutoConfiguration.class},
        after = io.github.stepprflow.core.metrics.WorkflowMetricsAutoConfiguration.class
)
@EnableConfigurationProperties(MonitorProperties.class)
@ConditionalOnProperty(prefix = "stepprflow.monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableMongoRepositories(basePackageClasses = {WorkflowExecutionRepository.class, OutboxMessageRepository.class})
@EnableScheduling
@EnableAsync
@Import({
        OpenApiConfig.class,
        WebSocketConfig.class,
        GlobalExceptionHandler.class,
        WorkflowController.class,
        CircuitBreakerController.class,
        HealthController.class,
        OutboxController.class,
        RegistryController.class,
        RetentionController.class,
        ExecutionPersistenceService.class,
        RetrySchedulerService.class,
        RetentionService.class,
        WorkflowQueryService.class,
        WorkflowCommandService.class,
        PayloadManagementService.class,
        WorkflowRegistryService.class,
        WorkflowMessageFactory.class,
        OutboxService.class,
        OutboxRelayService.class
})
public class MonitorAutoConfiguration {

    /**
     * Creates MongoClient using monitor properties.
     */
    @Bean
    @Primary
    public MongoClient mongoClient(MonitorProperties properties) {
        return MongoClients.create(properties.getMongodb().getUri());
    }

    /**
     * Creates MongoDatabaseFactory using monitor properties.
     */
    @Bean
    @Primary
    public MongoDatabaseFactory mongoDatabaseFactory(MongoClient mongoClient, MonitorProperties properties) {
        return new SimpleMongoClientDatabaseFactory(mongoClient, properties.getMongodb().getDatabase());
    }

}

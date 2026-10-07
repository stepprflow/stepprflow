# Steppr Flow

[![CI Build](https://github.com/stepprflow/stepprflow/actions/workflows/ci.yml/badge.svg)](https://github.com/stepprflow/stepprflow/actions/workflows/ci.yml)
[![Qodana](https://github.com/stepprflow/stepprflow/actions/workflows/qodana_code_quality.yml/badge.svg)](https://github.com/stepprflow/stepprflow/actions/workflows/qodana_code_quality.yml)
[![codecov](https://codecov.io/gh/stepprflow/stepprflow/branch/main/graph/badge.svg)](https://codecov.io/gh/stepprflow/stepprflow)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5+-green.svg)](https://spring.io/projects/spring-boot)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.stepprflow/stepprflow-core.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.stepprflow/stepprflow-core)
[![Website](https://img.shields.io/badge/Website-stepprflow.github.io-0ea5e9.svg)](https://stepprflow.github.io/stepprflow/)

A multi-broker workflow orchestration framework for Spring Boot applications.

Steppr Flow enables you to build resilient, async multi-step workflows with support for multiple message brokers (Kafka, RabbitMQ).

**🌐 [Website](https://stepprflow.github.io/stepprflow/) · 📦 [Maven Central](https://central.sonatype.com/artifact/io.github.stepprflow/stepprflow-core) · 🐳 [Docker Hub](https://hub.docker.com/r/alimhin/stepprflow-monitor)**

## Features

- **Annotation-driven workflows** - Define workflows using simple annotations
- **Multi-broker support** - Kafka (default) and RabbitMQ implementations
- **Automatic retries** - Built-in retry handling with exponential backoff
- **Dead Letter Queue (DLQ)** - Failed messages are automatically routed to DLQ
- **Security context propagation** - Security context flows automatically between steps
- **Step-by-step execution** - Each workflow step executes independently
- **Circuit breaker protection** - Built-in Resilience4j circuit breaker for broker failures
- **Distributed tracing** - Micrometer tracing integration for observability
- **Automatic service registration** - Zero-config broker-based registration with heartbeat and crash detection
- **Optional monitoring** - MongoDB persistence, REST API, and dashboard (opt-in)

## Modules

| Module | Description |
|--------|-------------|
| `stepprflow-core` | Core framework: annotations, models, interfaces, workflow engine |
| `stepprflow-spring-kafka` | Apache Kafka message broker implementation |
| `stepprflow-spring-rabbitmq` | RabbitMQ message broker implementation |
| `stepprflow-monitoring` | Monitoring, persistence (MongoDB), REST API, and dashboard (opt-in) |

## Quick Start

### 1. Add Dependencies

**Maven (Kafka - default):**
```xml
<dependency>
    <groupId>io.github.stepprflow</groupId>
    <artifactId>stepprflow-core</artifactId>
    <version>1.1.1</version>
</dependency>
<dependency>
    <groupId>io.github.stepprflow</groupId>
    <artifactId>stepprflow-spring-kafka</artifactId>
    <version>1.1.1</version>
</dependency>
```

**Maven (RabbitMQ):**
```xml
<dependency>
    <groupId>io.github.stepprflow</groupId>
    <artifactId>stepprflow-core</artifactId>
    <version>1.1.1</version>
</dependency>
<dependency>
    <groupId>io.github.stepprflow</groupId>
    <artifactId>stepprflow-spring-rabbitmq</artifactId>
    <version>1.1.1</version>
</dependency>
```

**Optional monitoring (adds MongoDB persistence + dashboard):**
```xml
<dependency>
    <groupId>io.github.stepprflow</groupId>
    <artifactId>stepprflow-monitoring</artifactId>
    <version>1.1.1</version>
</dependency>
```

### 2. Define a Workflow

```java
@Component
@Topic("order-processing")
public class OrderWorkflow {

    @Step(id = 1, label = "Validate order")
    public void validateOrder(OrderPayload payload) {
        // Validation logic
        if (payload.getItems().isEmpty()) {
            throw new IllegalArgumentException("Order must have items");
        }
    }

    @Step(id = 2, label = "Reserve inventory")
    public void reserveInventory(OrderPayload payload) {
        // Reserve items in inventory
        inventoryService.reserve(payload.getItems());
    }

    @Step(id = 3, label = "Process payment")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void processPayment(OrderPayload payload) {
        // Payment processing
        paymentService.charge(payload.getPaymentInfo());
    }

    @Step(id = 4, label = "Send confirmation")
    public void sendConfirmation(OrderPayload payload) {
        // Send confirmation email
        notificationService.sendOrderConfirmation(payload);
    }
}
```

### 3. Start a Workflow

```java
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final WorkflowStarter workflowStarter;

    public OrderController(WorkflowStarter workflowStarter) {
        this.workflowStarter = workflowStarter;
    }

    @PostMapping
    public ResponseEntity<String> createOrder(@RequestBody OrderRequest request) {
        OrderPayload payload = new OrderPayload(request);

        String executionId = workflowStarter.start("order-processing", payload);

        return ResponseEntity.accepted().body(executionId);
    }
}
```

### 4. Configure the Broker

**application.yml (Kafka - default):**
```yaml
stepprflow:
  broker: kafka
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: my-app-workers
    trusted-packages:
      - io.github.stepprflow.core.model
      - com.mycompany.workflow.payload
```

**application.yml (RabbitMQ):**
```yaml
stepprflow:
  broker: rabbitmq
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
    trusted-packages:
      - io.github.stepprflow.core.model
      - com.mycompany.workflow.payload
```

## Annotations

### @Topic

Marks a class as a workflow handler.

```java
@Component
@Topic(value = "order-processing", description = "Handles order lifecycle")
public class OrderWorkflow { }
```

| Attribute | Description | Default |
|-----------|-------------|---------|
| `value` | Topic/queue name | required |
| `description` | Documentation | `""` |
| `partitions` | Kafka partitions | `1` |
| `replication` | Kafka replication factor | `1` |

### @Step

Marks a method as a workflow step.

```java
@Step(id = 1, label = "Validate", description = "Validates input data")
public Payload validate(Payload payload) {
    return payload;
}
```

| Attribute | Description | Default |
|-----------|-------------|---------|
| `id` | Step order (must be unique) | required |
| `label` | Human-readable name | required |
| `description` | Documentation | `""` |
| `skippable` | Can skip on retry | `false` |
| `continueOnFailure` | Continue if step fails | `false` |

### @Timeout

Sets execution timeout for a step.

```java
@Step(id = 1, label = "External API call")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public Payload callExternalApi(Payload payload) {
    return payload;
}
```

### @OnSuccess / @OnFailure

Define callbacks for workflow completion.

```java
@OnSuccess
public void onComplete(WorkflowMessage message) {
    log.info("Workflow {} completed", message.getExecutionId());
}

@OnFailure
public void onFailed(WorkflowMessage message) {
    log.error("Workflow {} failed: {}",
        message.getExecutionId(),
        message.getErrorInfo().getMessage());
}
```

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      Application                            │
├─────────────────────────────────────────────────────────────┤
│  WorkflowStarter  │  @Topic Workflows  │  Step Handlers     │
├─────────────────────────────────────────────────────────────┤
│                    stepprflow-core                          │
│  ┌──────────────┐  ┌────────────────┐  ┌──────────────┐     │
│  │ StepExecutor │  │WorkflowRegistry│  │MessageBroker │     │
│  └──────────────┘  └────────────────┘  └──────────────┘     │
├─────────────────────────────────────────────────────────────┤
│  stepprflow-spring-kafka  │  stepprflow-spring-rabbitmq     │
├─────────────────────────────────────────────────────────────┤
│          Apache Kafka       │        RabbitMQ               │
└─────────────────────────────────────────────────────────────┘
```

## Message Flow

1. **Start**: `WorkflowStarter.start(topic, payload)` creates a `WorkflowMessage` and sends it to the broker
2. **Execute**: `StepExecutor` receives the message, finds the workflow, and executes the current step
3. **Next**: On success, the message is updated and sent back for the next step
4. **Complete**: After the last step, `@OnSuccess` callback is triggered
5. **Failure**: On error, retry logic is applied or `@OnFailure` callback is triggered

## Building

```bash
# Build all modules
mvn clean install

# Run tests
mvn test

# Build with samples and load tests
mvn clean install -Pfull
```

## Requirements

- Java 21+
- Spring Boot 3.5+
- Apache Kafka 3.x or RabbitMQ 3.12+
- MongoDB 7.0+ (only if using `stepprflow-monitoring`)
- Docker (for integration tests)

## License

Apache License 2.0

## Contributing

Contributions are welcome! Please read our contributing guidelines before submitting PRs.

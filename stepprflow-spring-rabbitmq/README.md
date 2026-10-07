# Steppr Flow Broker RabbitMQ

RabbitMQ implementation of the Steppr Flow message broker.

## Overview

This module provides RabbitMQ-based message transport for Steppr Flow workflows, ideal for traditional message queuing scenarios.

## Installation

```xml
<dependency>
    <groupId>io.github.stepprflow</groupId>
    <artifactId>stepprflow-spring-rabbitmq</artifactId>
    <version>1.1.1</version>
</dependency>
```

## Configuration

```yaml
stepprflow:
  enabled: true
  broker: rabbitmq
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
    virtual-host: /
    exchange: stepprflow.workflows
    prefetch-count: 10
    trusted-packages:
      - io.github.stepprflow.core.model
      - com.yourcompany.workflow
```

> **Note:** All RabbitMQ configuration is under `stepprflow.rabbitmq.*`. You do not need to configure `spring.rabbitmq.*` separately.

### Configuration Properties

| Property | Description | Default |
|----------|-------------|---------|
| `stepprflow.rabbitmq.host` | RabbitMQ host | `localhost` |
| `stepprflow.rabbitmq.port` | RabbitMQ port | `5672` |
| `stepprflow.rabbitmq.username` | Username | `guest` |
| `stepprflow.rabbitmq.password` | Password | `guest` |
| `stepprflow.rabbitmq.virtual-host` | Virtual host | `/` |
| `stepprflow.rabbitmq.exchange` | Exchange name | `stepprflow.workflows` |
| `stepprflow.rabbitmq.prefetch-count` | Prefetch count | `10` |
| `stepprflow.rabbitmq.dlq-suffix` | DLQ suffix | `.dlq` |
| `stepprflow.rabbitmq.trusted-packages` | Packages for deserialization | `[io.github.stepprflow.core.model]` |

## Features

- **Topic exchange**: Flexible routing patterns
- **Durable queues**: Messages survive broker restart
- **Dead Letter Exchange**: Failed messages routed to DLX
- **Manual acknowledgment**: Reliable message processing

## Queue Naming

| Queue Pattern | Description |
|---------------|-------------|
| `{workflow-topic}` | Main workflow queue |
| `{workflow-topic}.dlq` | Dead Letter Queue |
| `{workflow-topic}.retry` | Retry queue |
| `{workflow-topic}.completed` | Completed workflow queue |

## Usage

Steppr Flow auto-configures automatically with Spring Boot. No additional annotations required:

```java
@SpringBootApplication
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

Then define your workflows using `@Topic` and `@Step` annotations:

```java
@Component
@Topic("order-workflow")
public class OrderWorkflow {

    @Step(id = 1, label = "Validate")
    public void validate(OrderPayload payload) {
        // Validation logic
    }

    @Step(id = 2, label = "Process")
    public void process(OrderPayload payload) {
        // Processing logic
    }
}
```

## Docker Compose

```yaml
services:
  rabbitmq:
    image: rabbitmq:3.13-management-alpine
    ports:
      - "5672:5672"
      - "15672:15672"
    environment:
      RABBITMQ_DEFAULT_USER: guest
      RABBITMQ_DEFAULT_PASS: guest
```

## Management UI

Access RabbitMQ Management UI at `http://localhost:15672` (guest/guest).

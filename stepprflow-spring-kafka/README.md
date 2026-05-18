# Steppr Flow Broker Kafka

Apache Kafka implementation of the Steppr Flow message broker.

## Overview

This module provides Kafka-based message transport for Steppr Flow workflows, ideal for high-throughput, distributed streaming scenarios.

## Installation

```xml
<dependency>
    <groupId>io.github.stepprflow</groupId>
    <artifactId>stepprflow-spring-kafka</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Configuration

```yaml
stepprflow:
  enabled: true
  broker: kafka
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: my-app-workers
      concurrency: 3
      auto-offset-reset: earliest
    producer:
      acks: all
      retries: 3
    trusted-packages:
      - io.github.stepprflow.core.model
      - com.yourcompany.workflow
```

### Configuration Properties

| Property | Description | Default |
|----------|-------------|---------|
| `stepprflow.kafka.bootstrap-servers` | Kafka bootstrap servers | `localhost:9092` |
| `stepprflow.kafka.consumer.group-id` | Consumer group ID | `stepprflow-workflow-processor` |
| `stepprflow.kafka.consumer.concurrency` | Number of concurrent consumers | `1` |
| `stepprflow.kafka.consumer.auto-offset-reset` | Auto offset reset | `earliest` |
| `stepprflow.kafka.producer.acks` | Producer acknowledgments | `all` |
| `stepprflow.kafka.producer.retries` | Producer retries | `3` |
| `stepprflow.kafka.producer.batch-size` | Batch size in bytes | `16384` |
| `stepprflow.kafka.producer.linger-ms` | Linger time in ms | `5` |
| `stepprflow.kafka.topic-pattern` | Topic pattern for listener | `.*` |
| `stepprflow.kafka.trusted-packages` | Packages for deserialization | `[io.github.stepprflow.core.model]` |

## Features

- **Partition-based ordering**: Messages with the same execution ID go to the same partition
- **Manual acknowledgment**: Reliable message processing with manual offset commit
- **Snappy compression**: Optimized producer compression
- **Batch fetching**: Consumer performance optimizations for high throughput

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

## Topic isolation

By default, all consumers using the same Kafka cluster subscribe to **all**
workflow topics (`topicPattern=".*"`). The listener filters out messages on
topics not registered locally via `@Topic` — they are silently acknowledged
and dropped. This prevents:

- log pollution from foreign workflow messages
- side-effects (Spring event publish, StepExecutor.execute) on workflows
  that don't belong to this service

To further restrict at the broker subscription level (optional optimization),
set `stepprflow.kafka.topic-pattern` to a regex matching only your service's
topics in `application.yml`.

## Docker Compose

```yaml
services:
  kafka:
    image: confluentinc/cp-kafka:7.5.0
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@localhost:9093
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      CLUSTER_ID: MkU3OEVBNTcwNTJENDM2Qk
```

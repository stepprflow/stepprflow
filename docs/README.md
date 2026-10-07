# Steppr Flow

**Steppr Flow** is a powerful, lightweight workflow orchestration framework for Spring Boot applications. It enables you to build resilient, distributed, multi-step workflows with minimal boilerplate code.

## What is Steppr Flow?

Steppr Flow is designed to help you orchestrate complex business processes across microservices. Instead of writing custom state machines or complex saga implementations, you define workflows using simple annotations.

### Key Features

- **Declarative Workflow Definition** - Define workflows using `@Topic`, `@Step`, `@OnSuccess`, `@OnFailure` annotations
- **Message Broker Abstraction** - Switch between Kafka and RabbitMQ without code changes
- **Automatic Retry with Backoff** - Configurable exponential backoff retry strategy
- **Dead Letter Queue (DLQ)** - Failed messages are automatically routed to DLQ
- **Security Context Propagation** - Security context flows automatically between steps
- **Circuit Breaker Protection** - Built-in Resilience4j circuit breaker for broker failures
- **Distributed Tracing** - Micrometer tracing integration for observability
- **Broker-based Service Registration** - Automatic zero-config registration of workflow definitions via the shared message broker
- **Optional Monitoring** - MongoDB persistence, REST API, WebSocket dashboard (opt-in)

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Your Application                              │
├─────────────────────────────────────────────────────────────────────────┤
│  ┌──────────────────┐  ┌───────────────────┐  ┌──────────────────┐      │
│  │  OrderWorkflow   │  │  PaymentWorkflow  │  │  ShippingWorkflow│      │
│  │  @Topic("order") │  │  @Topic("payment")│  │  @Topic("ship")  │      │
│  │                  │  │                   │  │                  │      │
│  │  @Step(1) ───────┼──┼─► @Step(1) ───────┼──┼─► @Step(1)       │      │
│  │  @Step(2)        │  │   @Step(2)        │  │   @Step(2)       │      │
│  │  @Step(3)        │  │   @Step(3)        │  │   @Step(3)       │      │
│  └──────────────────┘  └───────────────────┘  └──────────────────┘      │
│           │                     │                     │                 │
│           ▼                     ▼                     ▼                 │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │                       stepprflow-core                           │    │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │    │
│  │  │WorkflowStart│  │ StepExecutor│  │ WorkflowRegistry        │  │    │
│  │  └─────────────┘  └─────────────┘  └─────────────────────────┘  │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                                │                                        │
└────────────────────────────────┼────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         Message Broker Layer                            │
│  ┌────────────────────────────┐    ┌────────────────────────────┐       │
│  │  stepprflow-spring-kafka  │ OR │ stepprflow-spring-rabbitmq │       │
│  └────────────────────────────┘    └────────────────────────────┘       │
│                     │                          │                        │
│                     ▼                          ▼                        │
│              ┌──────────┐              ┌────────────┐                   │
│              │  Kafka   │              │  RabbitMQ  │                   │
│              └──────────┘              └────────────┘                   │
└─────────────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                   Monitoring (opt-in: stepprflow-monitoring)            │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │  • Execution persistence (MongoDB)                                │  │
│  │  • Metrics collection                                             │  │
│  │  • Retry scheduling                                               │  │
│  │  • REST API + WebSocket broadcasts                                │  │
│  │  • Dashboard UI                                                   │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                     │                          │                        │
│                     ▼                          ▼                        │
│              ┌──────────┐              ┌────────────┐                   │
│              │ MongoDB  │              │  Browser   │                   │
│              └──────────┘              └────────────┘                   │
└─────────────────────────────────────────────────────────────────────────┘
```

## Module Structure

| Module | Description |
|--------|-------------|
| `stepprflow-core` | Core framework: annotations, models, interfaces, workflow engine |
| `stepprflow-spring-kafka` | Apache Kafka message broker implementation |
| `stepprflow-spring-rabbitmq` | RabbitMQ message broker implementation |
| `stepprflow-monitoring` | Monitoring, persistence, metrics, retry scheduling, REST API, and dashboard (opt-in) |
| `stepprflow-ui` | Vue.js 3 + Tailwind CSS frontend (built assets embedded in monitoring) |
| `stepprflow-samples` | Example application with Kafka and RabbitMQ profiles |
| `stepprflow-load-tests` | Performance benchmarks (Gatling) |

## How It Works

### 1. Define a Workflow

```java
@Component
@Topic("order-workflow")
public class OrderWorkflow {

    @Step(id = 1, label = "Validate Order")
    public OrderPayload validate(OrderPayload payload) {
        // Validation logic
        return payload;
    }

    @Step(id = 2, label = "Process Payment")
    @Timeout(30)
    public OrderPayload payment(OrderPayload payload) {
        // Payment logic
        return payload;
    }

    @Step(id = 3, label = "Ship Order")
    public OrderPayload ship(OrderPayload payload) {
        // Shipping logic
        return payload;
    }

    @OnSuccess
    public void onComplete(WorkflowMessage message) {
        log.info("Order completed: {}", message.getExecutionId());
    }

    @OnFailure
    public void onFailed(WorkflowMessage message) {
        log.error("Order failed: {}", message.getErrorInfo());
    }
}
```

### 2. Start a Workflow

```java
@RestController
public class OrderController {

    private final WorkflowStarter workflowStarter;

    @PostMapping("/orders")
    public ResponseEntity<?> createOrder(@RequestBody OrderRequest request) {
        OrderPayload payload = new OrderPayload(request);
        String executionId = workflowStarter.start("order-workflow", payload);
        return ResponseEntity.accepted().body(Map.of("executionId", executionId));
    }
}
```

### 3. Monitor Execution

The workflow automatically:
- Creates a unique execution ID
- Publishes each step to the message broker
- Tracks progress in MongoDB (if monitoring enabled)
- Broadcasts updates via WebSocket
- Retries failed steps with exponential backoff
- Routes permanently failed messages to DLQ

## Workflow States

```
    ┌─────────┐
    │ PENDING │ ◄── Workflow started, waiting for processing
    └────┬────┘
         │
         ▼
  ┌─────────────┐
  │ IN_PROGRESS │ ◄── Step is being executed
  └──────┬──────┘
         │
    ┌────┴────┐
    │         │
    ▼         ▼
┌───────┐ ┌────────┐
│ PASSED│ │ FAILED │
└───┬───┘ └────┬───┘
    │          │
    │    ┌─────┴───────────┐
    │    │                 │
    │    ▼                 ▼
    │ ┌─────────────┐ ┌───────────┐
    │ │RETRY_PENDING│ │   (DLQ)   │
    │ └──────┬──────┘ └───────────┘
    │        │
    │        ▼
    │   ┌─────────────┐
    │   │ IN_PROGRESS │
    │   └──────┬──────┘
    │          │
    └────┬─────┘
         │
         ▼
   ┌───────────┐
   │ COMPLETED │ ◄── All steps passed
   └───────────┘
```

## Quick Start

### 1. Add Dependency

**Core + Kafka:**
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

### 2. Configure

```yaml
stepprflow:
  enabled: true
  retry:
    max-attempts: 3
    initial-delay: 1s

stepprflow:
  kafka:
    bootstrap-servers: localhost:9092
```

### 3. Create Workflow

```java
@Component
@Topic("my-workflow")
public class MyWorkflow {

    @Step(id = 1, label = "First Step")
    public MyPayload step1(MyPayload payload) {
        // Your logic
        return payload;
    }

    @Step(id = 2, label = "Second Step")
    public MyPayload step2(MyPayload payload) {
        // Your logic
        return payload;
    }
}
```

### 4. Start Workflow

```java
workflowStarter.start("my-workflow", new MyPayload());
```

## Documentation

- [Getting Started Guide](./getting-started.md) - Complete setup tutorial
- [Message Brokers](./brokers.md) - Kafka & RabbitMQ configuration
- [Security Context Propagation](./security.md) - JWT and authentication propagation
- [Monitoring & Dashboard](./monitoring.md) - Setting up the monitoring dashboard
- [Modules](./modules.md) - Detailed module descriptions and dependency graph
- [Development Guide](./development.md) - Local development setup and contributing

## Requirements

- Java 21+
- Spring Boot 3.5+
- Apache Kafka 3.x+ or RabbitMQ 3.x+
- MongoDB 7.0+ (only if using `stepprflow-monitoring`)

## License

Apache License 2.0

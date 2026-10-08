# StepprFlow - Architecture Documentation

## Overview

**StepprFlow** is a multi-broker workflow orchestration framework for Spring Boot. It enables creating resilient asynchronous workflows with Kafka and RabbitMQ support.

---

## Module Structure

```
stepprflow-parent/
├── stepprflow-core              # Foundation (annotations, abstractions, models, registration)
├── stepprflow-spring-kafka      # Kafka implementation
├── stepprflow-spring-rabbitmq   # RabbitMQ implementation
├── stepprflow-monitoring        # Monitoring server (MongoDB, REST API, WebSocket, Dashboard UI)
├── stepprflow-ui                # Vue.js frontend (built into stepprflow-monitoring)
├── stepprflow-samples           # Examples (Kafka/RabbitMQ profiles)
└── stepprflow-load-tests        # Performance benchmarks (Gatling)
```

---

## Dependency Graph

```
                         ┌─────────────────────┐
                         │   stepprflow-core    │
                         └──────────┬──────────┘
              ┌──────────────────────┼──────────────────────┐
              │                      │                      │
              ▼                      ▼                      ▼
   ┌──────────────────┐   ┌──────────────────┐   ┌──────────────────┐
   │  spring-kafka    │   │ spring-rabbitmq  │   │   monitoring     │
   └────────┬─────────┘   └────────┬─────────┘   │  (+ Vue.js UI)  │
            │                      │              └──────────────────┘
            └──────────┬───────────┘
                       │
                       ▼
             ┌──────────────────┐
             │     samples      │
             └──────────────────┘
```

---

## Module Responsibilities

| Module | Responsibility |
|--------|----------------|
| **core** | Annotations (`@Topic`, `@Step`), interfaces (`MessageBroker`, `StepprFlow`), models (`WorkflowMessage`), workflow engine, **broker-based registration client** |
| **spring-kafka** | `KafkaMessageBroker`, `KafkaMessageListener`, Kafka auto-configuration |
| **spring-rabbitmq** | `RabbitMQMessageBroker`, `RabbitMQMessageListener`, RabbitMQ auto-configuration |
| **monitoring** | MongoDB persistence, REST API, real-time WebSocket, metrics, retry scheduling, **registration handler**, Vue.js dashboard |
| **ui** | Vue.js 3 + Tailwind CSS frontend (built assets embedded in monitoring) |
| **samples** | Usage examples (`kafka` and `rabbitmq` profiles) |

---

## Workflow Execution Flow

```
1. START
   Application → WorkflowStarter.start("order-workflow", payload)
                          │
                          ▼
   WorkflowMessage created (executionId, status=PENDING, step=1)
                          │
                          ▼
   MessageBroker.send() → Kafka Topic / RabbitMQ Queue

2. CONSUMPTION
   MessageListener receives message
                          │
                          ├──────────────────────────────┐
                          │                              │
                          ▼                              ▼
   StepExecutor.execute()              ExecutionPersistenceService
   - Loads WorkflowDefinition          - Saves to MongoDB (opt-in)
   - Deserializes payload              - Step history tracking
   - Invokes @Step method
   - Handles success/failure
                          │
                          ▼
3. RESULT
   ├─ SUCCESS → Next step or COMPLETED
   ├─ RETRYABLE FAILURE → Retry with exponential backoff
   └─ FATAL FAILURE → Dead Letter Queue (DLQ)

4. CALLBACKS
   @OnSuccess or @OnFailure invoked at the end
```

---

## Service Registration Flow

Services automatically register their workflow definitions with the monitoring server via the message broker. No HTTP configuration needed — zero additional config.

```
                    stepprflow.registration topic
                    ═══════════════════════════════

  ┌───────────────────┐        ┌──────────┐        ┌───────────────────┐
  │  Service Agent    │  send  │  Broker  │  recv  │    Monitoring     │
  │  (core +          │───────►│  Kafka / │───────►│  Registration     │
  │   kafka/rabbitmq) │        │ RabbitMQ │        │  MessageHandler   │
  └───────────────────┘        └──────────┘        └───────────────────┘

  Lifecycle:
  ┌──────────┐   @PostConstruct    ┌───────────┐   every 30s   ┌───────────┐
  │  Startup ├────────────────────►│ REGISTER  ├──────────────►│ HEARTBEAT │
  └──────────┘                     └───────────┘               └─────┬─────┘
                                                                     │
                                         ┌───────────┐   @PreDestroy │
                                         │DEREGISTER │◄──────────────┘
                                         └───────────┘

  Messages use WorkflowMessage envelope with metadata:
  ┌─────────────────────────────────────────────────┐
  │  topic: "stepprflow.registration"               │
  │  metadata:                                      │
  │    registration.action: REGISTER|HEARTBEAT|     │
  │                         DEREGISTER              │
  │    registration.instanceId: <uuid>              │
  │  payload: WorkflowRegistrationRequest (REGISTER)│
  │           null (HEARTBEAT/DEREGISTER)           │
  │  status: COMPLETED (ignored by step listeners)  │
  └─────────────────────────────────────────────────┘

  Crash detection:
  - If no heartbeat received for 90s → instance marked stale
  - Cleanup scheduler runs every 30s
  - Workflow marked INACTIVE when all instances removed
```

---

## Core Data Model

### WorkflowMessage
```
executionId      : Unique UUID
correlationId    : Trace ID
topic            : Workflow name
currentStep      : Current step (1-based)
totalSteps       : Total number of steps
status           : PENDING | IN_PROGRESS | COMPLETED | FAILED | CANCELLED | RETRY_PENDING | TIMED_OUT | PAUSED | SKIPPED | PASSED
payload          : Business data (JSON)
retryInfo        : Attempts, delay, previous error
errorInfo        : Code, message, stacktrace
metadata         : Additional context
```

### WorkflowExecution (MongoDB)
```javascript
{
  _id: "executionId",
  topic: "order-workflow",
  status: "COMPLETED",
  stepHistory: [
    { stepId: 1, stepLabel: "Validate", status: "PASSED", durationMs: 45 },
    { stepId: 2, stepLabel: "Process", status: "PASSED", durationMs: 1200 }
  ],
  payload: { ... },
  createdAt: timestamp,
  completedAt: timestamp,
  durationMs: 1245
}
```

---

## Essential Configuration

### Service (agent)

```yaml
spring:
  application:
    name: my-service

stepprflow:
  enabled: true
  broker: kafka  # or rabbitmq

  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: my-app-workers
    trusted-packages:
      - io.github.stepprflow.core.model
      - com.mycompany.model

  # Deserialization: by default a step's payload is deserialized into the
  # receiver's own @Step parameter type and the sender's payloadType is never
  # resolved via reflection (no trusted-packages needed, services decoupled).
  # The sender's payloadType is only resolved through the trusted-packages
  # allowlist under opt-in strict mode:
  #   security:
  #     trusted-package-enforcement: true

  # Registration is automatic when a MessageBroker bean is present.
  # No server URL needed — registration goes through the broker.
  registration:
    enabled: true                # default
    heartbeat-interval-seconds: 30  # default

  retry:
    max-attempts: 3
    initial-delay: 1s
    multiplier: 2.0
    max-delay: 30s

  dlq:
    enabled: true
    suffix: ".dlq"
```

### Monitoring server

```yaml
stepprflow:
  monitor:
    enabled: true
    mongodb:
      uri: mongodb://localhost:27017/stepprflow
      database: stepprflow
    registry:
      instance-timeout: 90s    # stale after 90s without heartbeat
      cleanup-interval: 30s    # cleanup check every 30s
```

---

## Usage Example

```java
@Service
@Topic("order-workflow")
public class OrderWorkflow implements StepprFlow {

    @Step(id = 1, label = "Validate Order")
    public void validate(OrderPayload payload) {
        if (payload.getItems().isEmpty())
            throw new IllegalArgumentException("No items");
    }

    @Step(id = 2, label = "Process Payment")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void payment(OrderPayload payload) {
        paymentService.charge(payload);
    }

    @Step(id = 3, label = "Ship Order")
    public void ship(OrderPayload payload) {
        shippingService.createShipment(payload);
    }

    @OnSuccess
    public void onComplete(WorkflowMessage msg) {
        log.info("Order {} completed", msg.getExecutionId());
    }

    @OnFailure
    public void onFailed(WorkflowMessage msg) {
        // Compensation logic
    }
}
```

---

## Tech Stack

| Component | Technology |
|-----------|------------|
| Language | Java 21+ |
| Framework | Spring Boot 3.5+ |
| Default Broker | Apache Kafka |
| Alternative Broker | RabbitMQ |
| Persistence | MongoDB |
| Metrics | Micrometer |
| Resilience | Resilience4j |
| Frontend | Vue.js 3 + Tailwind CSS |
| Build | Maven |
| Tests | JUnit 5, Testcontainers, Pitest |

---

## Architectural Patterns

- **Pub/Sub**: Communication via Kafka topics / RabbitMQ queues
- **Event Sourcing**: `WorkflowMessageEvent` for asynchronous reactions
- **State Machine**: `WorkflowStatus` transitions
- **Strategy**: `MessageBroker` abstracts Kafka/RabbitMQ
- **Exponential Backoff Retry**: Transient failure handling
- **Circuit Breaker**: Protection against failure cascades (Resilience4j)
- **Dead Letter Queue**: Failed message isolation
- **Broker-based Registration**: Zero-config service discovery via the shared message broker
- **Heartbeat / Stale Detection**: Automatic crash detection and workflow deactivation

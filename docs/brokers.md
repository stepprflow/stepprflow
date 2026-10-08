# Message Broker Configuration

Steppr Flow supports multiple message brokers. This guide covers configuration for each supported broker.

## Kafka

Apache Kafka is the default broker for high-throughput, distributed workflows.

### Dependencies

```xml
<dependency>
    <groupId>io.github.stepprflow</groupId>
    <artifactId>stepprflow-spring-kafka</artifactId>
    <version>${stepprflow.version}</version>
</dependency>
```

### Configuration

```yaml
stepprflow:
  broker: kafka
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: ${spring.application.name}-workers
      auto-offset-reset: earliest
      concurrency: 3
    producer:
      acks: all
      retries: 3
    trusted-packages:
      - io.github.stepprflow.core.model
      - com.mycompany.model
```

> **Note.** `trusted-packages` is only consulted under the opt-in strict mode
> `stepprflow.security.trusted-package-enforcement: true`. By default, payloads
> are deserialized into the receiver's `@Step` parameter type and the sender's
> `payloadType` is never resolved via reflection — no `trusted-packages` entries
> are required to receive cross-service messages.

### Advanced Configuration

```yaml
# Advanced Kafka properties are passed through Spring Kafka's native configuration.
# These complement the stepprflow.kafka.* properties above.
spring:
  kafka:
    # SSL Configuration
    ssl:
      key-store-location: classpath:kafka.keystore.jks
      key-store-password: changeit
      trust-store-location: classpath:kafka.truststore.jks
      trust-store-password: changeit

    # SASL Configuration
    properties:
      security.protocol: SASL_SSL
      sasl.mechanism: PLAIN
      sasl.jaas.config: >
        org.apache.kafka.common.security.plain.PlainLoginModule required
        username="user"
        password="password";
```

### Multiple Topics

Each workflow creates its own topic. Topics are auto-created based on `@Topic` annotations:

```java
@Topic(value = "orders", partitions = 6, replication = 3)
public class OrderWorkflow implements StepprFlow { }

@Topic(value = "payments", partitions = 3, replication = 3)
public class PaymentWorkflow implements StepprFlow { }
```

---

## RabbitMQ

RabbitMQ is ideal for simpler deployments and when you need flexible routing.

### Dependencies

```xml
<dependency>
    <groupId>io.github.stepprflow</groupId>
    <artifactId>stepprflow-spring-rabbitmq</artifactId>
    <version>${stepprflow.version}</version>
</dependency>
```

### Configuration

```yaml
stepprflow:
  broker: rabbitmq
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
    virtual-host: /
    exchange: stepprflow-exchange
    prefetch-count: 10
```

### Advanced Configuration

```yaml
# Advanced RabbitMQ properties are passed through Spring AMQP's native configuration.
# These complement the stepprflow.rabbitmq.* properties above.
spring:
  rabbitmq:
    # Connection settings
    connection-timeout: 60000
    requested-heartbeat: 60

    # SSL Configuration
    ssl:
      enabled: true
      key-store: classpath:keystore.p12
      key-store-password: changeit
      trust-store: classpath:truststore.jks
      trust-store-password: changeit

    # Publisher confirms
    publisher-confirms: true
    publisher-returns: true

    # Listener settings
    listener:
      simple:
        concurrency: 3
        max-concurrency: 10
        prefetch: 1
        acknowledge-mode: manual
```

### Queue Naming

Queues are automatically created based on workflow topics:

| Workflow Topic | Queue Name | Routing Key |
|----------------|------------|-------------|
| `order-processing` | `order-processing` | `order-processing` |
| `payment-flow` | `payment-flow` | `payment-flow` |

### Dead Letter Queues

Configure DLQ for failed messages:

```yaml
stepprflow:
  rabbitmq:
    dead-letter:
      enabled: true
      exchange: stepprflow-dlx
      routing-key-suffix: .dlq
```

---

## Switching Brokers

To switch brokers, change the dependency and configuration:

### From Kafka to RabbitMQ

1. Replace dependency:
```xml
<!-- Remove -->
<dependency>
    <groupId>io.github.stepprflow</groupId>
    <artifactId>stepprflow-spring-kafka</artifactId>
</dependency>

<!-- Add -->
<dependency>
    <groupId>io.github.stepprflow</groupId>
    <artifactId>stepprflow-spring-rabbitmq</artifactId>
</dependency>
```

2. Update configuration:
```yaml
stepprflow:
  broker: rabbitmq  # Changed from 'kafka'
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
# Remove stepprflow.kafka.* properties
```

3. **No code changes required** - Your workflows remain the same!

---

## Custom Broker Implementation

You can implement your own broker by implementing `MessageBroker`:

```java
@Component
public class CustomMessageBroker implements MessageBroker {

    @Override
    public void send(String destination, WorkflowMessage message) {
        // Send message to your broker
    }

    @Override
    public CompletableFuture<Void> sendAsync(String destination, WorkflowMessage message) {
        return CompletableFuture.runAsync(() -> send(destination, message));
    }

    @Override
    public void sendSync(String destination, WorkflowMessage message) {
        // Synchronous send with confirmation
    }

    @Override
    public String getBrokerType() {
        return "custom";
    }

    @Override
    public boolean isAvailable() {
        // Check broker connectivity
        return true;
    }
}
```

Register your broker with auto-configuration:

```java
@Configuration
@ConditionalOnProperty(name = "stepprflow.broker", havingValue = "custom")
public class CustomBrokerAutoConfiguration {

    @Bean
    public MessageBroker messageBroker() {
        return new CustomMessageBroker();
    }
}
```

---

## Broker Comparison

| Feature | Kafka | RabbitMQ |
|---------|-------|----------|
| Throughput | Very High | High |
| Ordering | Per-partition | Per-queue |
| Persistence | Log-based | Queue-based |
| Replay | Yes | No (by default) |
| Routing | Topics/Partitions | Exchanges/Bindings |
| Clustering | Built-in | Requires setup |
| Use case | Event streaming, high volume | Task queues, RPC |

### When to use Kafka

- High-throughput requirements (100k+ messages/sec)
- Need to replay/reprocess messages
- Event sourcing architectures
- Multi-consumer scenarios

### When to use RabbitMQ

- Simpler operational requirements
- Flexible routing needs
- Request-reply patterns
- Lower volume workloads

---

## Circuit Breaker

Steppr Flow includes built-in circuit breaker protection for message brokers using [Resilience4j](https://resilience4j.readme.io/). This prevents cascade failures when a broker becomes unavailable.

### How it works

The circuit breaker wraps all broker operations and monitors failure rates:

1. **CLOSED** (normal): All calls pass through to the broker
2. **OPEN** (failing): Calls are rejected immediately with `CircuitBreakerOpenException`
3. **HALF_OPEN** (recovering): Limited calls allowed to test if broker has recovered

### Configuration

```yaml
stepprflow:
  circuit-breaker:
    enabled: true                                    # Enable/disable circuit breaker
    failure-rate-threshold: 50                       # % of failures to open circuit
    slow-call-rate-threshold: 100                    # % of slow calls to open circuit
    slow-call-duration-threshold: 10s                # Duration to consider a call slow
    sliding-window-size: 10                          # Number of calls to evaluate
    minimum-number-of-calls: 5                       # Min calls before evaluating
    permitted-number-of-calls-in-half-open-state: 3  # Calls allowed in half-open
    wait-duration-in-open-state: 30s                 # Time to wait before half-open
    automatic-transition-from-open-to-half-open-enabled: true
```

### Default Values

If no configuration is provided, sensible defaults are used:

| Property | Default |
|----------|---------|
| `enabled` | `true` |
| `failure-rate-threshold` | `50%` |
| `slow-call-rate-threshold` | `100%` |
| `slow-call-duration-threshold` | `10s` |
| `sliding-window-size` | `10` |
| `minimum-number-of-calls` | `5` |
| `permitted-number-of-calls-in-half-open-state` | `3` |
| `wait-duration-in-open-state` | `30s` |
| `automatic-transition-from-open-to-half-open-enabled` | `true` |

### Handling Circuit Breaker Open

When the circuit is open, broker operations throw `CircuitBreakerOpenException`:

```java
try {
    workflowExecutor.start(new OrderPayload(orderId));
} catch (CircuitBreakerOpenException e) {
    log.warn("Broker {} is unavailable, circuit is {}",
        e.getCircuitBreakerName(), e.getState());
    // Handle gracefully - retry later, use fallback, etc.
}
```

### Monitoring via REST API

The circuit breaker status is available via the monitoring API:

```bash
# Get all circuit breakers status
GET /api/circuit-breakers

# Get specific circuit breaker
GET /api/circuit-breakers/{name}

# Get circuit breaker configuration
GET /api/circuit-breakers/config

# Reset a circuit breaker to CLOSED
POST /api/circuit-breakers/{name}/reset
```

Example response:

```json
{
  "name": "broker-kafka",
  "state": "CLOSED",
  "successfulCalls": 150,
  "failedCalls": 2,
  "notPermittedCalls": 0,
  "bufferedCalls": 10,
  "slowCalls": 0,
  "failureRate": 1.3,
  "slowCallRate": 0.0
}
```

### Monitoring via UI

The Steppr Flow UI displays circuit breaker information in the **Metrics** page:

- Current state of each circuit breaker (CLOSED/OPEN/HALF_OPEN)
- Success/failure counts and rates
- Configuration settings
- Reset button for OPEN/HALF_OPEN circuits

### Disabling Circuit Breaker

To disable the circuit breaker (not recommended for production):

```yaml
stepprflow:
  circuit-breaker:
    enabled: false
```

---

## Distributed Tracing

Steppr Flow includes built-in distributed tracing using [Micrometer Tracing](https://micrometer.io/docs/tracing). This provides observability across your workflow executions.

### How it works

Every workflow step execution is automatically traced with:
- **Span name**: `stepprflow.workflow.step`
- **Contextual name**: `{topic}.{stepLabel}` (e.g., `order-workflow.validate-order`)

### Tags (Low Cardinality)

| Tag | Description |
|-----|-------------|
| `stepprflow.workflow.topic` | Workflow topic name |
| `stepprflow.workflow.step.id` | Step ID (1, 2, 3...) |
| `stepprflow.workflow.step.label` | Step label |
| `stepprflow.workflow.status` | Execution status (SUCCESS, FAILED) |

### Tags (High Cardinality)

| Tag | Description |
|-----|-------------|
| `stepprflow.workflow.execution.id` | Unique execution ID |
| `stepprflow.workflow.correlation.id` | Correlation ID for tracing related operations |

### Enabling Tracing

Tracing is enabled automatically when you add a tracer bridge to your project:

**For Zipkin/Brave:**
```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
<dependency>
    <groupId>io.zipkin.reporter2</groupId>
    <artifactId>zipkin-reporter-brave</artifactId>
</dependency>
```

**For OpenTelemetry:**
```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

### Configuration

```yaml
# For Zipkin
management:
  tracing:
    sampling:
      probability: 1.0  # Sample 100% of requests
  zipkin:
    tracing:
      endpoint: http://localhost:9411/api/v2/spans

# For OpenTelemetry
management:
  otlp:
    tracing:
      endpoint: http://localhost:4318/v1/traces
```

### Using WorkflowTracing Directly

You can also use `WorkflowTracing` directly for custom instrumentation:

```java
@Autowired
private WorkflowTracing workflowTracing;

public void processOrder(WorkflowMessage message, StepDefinition step) {
    workflowTracing.traceStep(message, step, () -> {
        // Your step logic here
        return result;
    });
}
```

### Integration with Logging

Add trace IDs to your logs with Spring Boot's logging integration:

```yaml
logging:
  pattern:
    level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]"
```

This produces logs like:
```
INFO  [order-service,abc123,def456] Processing order ORD-001
```
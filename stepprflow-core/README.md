# Steppr Flow Core

Spring Boot framework for orchestrating asynchronous multi-step workflows via pluggable message brokers.

## Features

- Multi-step workflows with asynchronous execution
- Declarative annotations (`@Topic`, `@Step`, `@OnSuccess`, `@OnFailure`)
- Pluggable message broker abstraction (add `stepprflow-spring-kafka` or `stepprflow-spring-rabbitmq`)
- Automatic retry with exponential backoff
- Dead Letter Queue (DLQ) for failed messages
- Security context propagation between steps
- Configurable timeout per step or workflow
- Automatic broker-based registration with heartbeat and crash detection

## Installation

### Maven

```xml
<dependency>
    <groupId>io.github.stepprflow</groupId>
    <artifactId>stepprflow-core</artifactId>
    <version>${stepprflow.version}</version>
</dependency>
```

> **Note:** You also need a broker module: add `stepprflow-spring-kafka` for Kafka or `stepprflow-spring-rabbitmq` for RabbitMQ.

## Quick Start

### 1. Configuration

Add configuration in `application.yml`:

```yaml
stepprflow:
  enabled: true
  retry:
    max-attempts: 3
    initial-delay: 1s
    max-delay: 5m
    multiplier: 2.0
```

Broker-specific configuration is provided by the broker module you include (`stepprflow-spring-kafka` or `stepprflow-spring-rabbitmq`).

### 2. Create a Payload

Define a class to carry workflow data:

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderPayload {
    private String orderId;
    private String customerId;
    private List<String> items;
    private BigDecimal totalAmount;
    private OrderStatus status;
}
```

### 3. Create a Workflow

Create a class annotated with `@Topic` and `@Component`:

```java
@Slf4j
@Component
@Topic(value = "order-processing", description = "Order processing workflow")
public class OrderWorkflow {

    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final NotificationService notificationService;

    public OrderWorkflow(OrderRepository orderRepository,
                         PaymentService paymentService,
                         NotificationService notificationService) {
        this.orderRepository = orderRepository;
        this.paymentService = paymentService;
        this.notificationService = notificationService;
    }

    @Step(id = 1, label = "Validate order")
    public void validateOrder(OrderPayload payload) {
        log.info("Validating order: {}", payload.getOrderId());

        if (payload.getItems() == null || payload.getItems().isEmpty()) {
            throw new IllegalArgumentException("Order must contain at least one item");
        }

        if (payload.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        payload.setStatus(OrderStatus.VALIDATED);
    }

    @Step(id = 2, label = "Process payment")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void processPayment(OrderPayload payload) {
        log.info("Processing payment for: {}", payload.getOrderId());

        paymentService.charge(payload.getCustomerId(), payload.getTotalAmount());
        payload.setStatus(OrderStatus.PAID);
    }

    @Step(id = 3, label = "Save to database")
    public void saveOrder(OrderPayload payload) {
        log.info("Saving order: {}", payload.getOrderId());

        orderRepository.save(payload);
        payload.setStatus(OrderStatus.CONFIRMED);
    }

    @Step(id = 4, label = "Notify customer", continueOnFailure = true)
    public void notifyCustomer(OrderPayload payload) {
        log.info("Notifying customer: {}", payload.getCustomerId());

        notificationService.sendOrderConfirmation(
            payload.getCustomerId(),
            payload.getOrderId()
        );
    }

    @OnSuccess
    public void onWorkflowSuccess(OrderPayload payload) {
        log.info("Workflow completed successfully for: {}", payload.getOrderId());
    }

    @OnFailure
    public void onWorkflowFailure(OrderPayload payload, Exception error) {
        log.error("Workflow failed for: {} - Error: {}",
            payload.getOrderId(), error.getMessage());
    }
}
```

### 4. Start a Workflow

Inject `WorkflowStarter` and start the workflow:

```java
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final WorkflowStarter workflowStarter;

    public OrderController(WorkflowStarter workflowStarter) {
        this.workflowStarter = workflowStarter;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> createOrder(@RequestBody CreateOrderRequest request) {
        OrderPayload payload = new OrderPayload();
        payload.setOrderId(UUID.randomUUID().toString());
        payload.setCustomerId(request.getCustomerId());
        payload.setItems(request.getItems());
        payload.setTotalAmount(request.getTotalAmount());
        payload.setStatus(OrderStatus.PENDING);

        // Start the workflow
        String executionId = workflowStarter.start("order-processing", payload);

        return ResponseEntity.accepted()
            .body(Map.of(
                "executionId", executionId,
                "orderId", payload.getOrderId()
            ));
    }

    @PostMapping("/{executionId}/resume")
    public ResponseEntity<Void> resumeOrder(@PathVariable String executionId,
                                            @RequestParam(required = false) Integer fromStep) {
        workflowStarter.resume(executionId, fromStep);
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/{executionId}")
    public ResponseEntity<Void> cancelOrder(@PathVariable String executionId) {
        workflowStarter.cancel(executionId);
        return ResponseEntity.noContent().build();
    }
}
```

### 5. Async Start (optional)

For non-blocking startup:

```java
CompletableFuture<String> future = workflowStarter.startAsync("order-processing", payload);

future.thenAccept(executionId -> {
    log.info("Workflow started with ID: {}", executionId);
});
```

## Annotations

| Annotation | Target | Description |
|------------|--------|-------------|
| `@Topic` | Class | Defines the message destination for the workflow |
| `@Step` | Method | Defines a workflow step |
| `@OnSuccess` | Method | Callback executed on success |
| `@OnFailure` | Method | Callback executed on failure |
| `@Timeout` | Class/Method | Defines a timeout |

## Annotation Options

### @Step

```java
@Step(
    id = 1,                      // Step order/identifier (required)
    label = "My step",           // Readable label (required)
    description = "...",         // Description (optional)
    skippable = false,           // Can be skipped on retry
    continueOnFailure = false    // Continue even on error
)
```

### @Topic

```java
@Topic(
    value = "my-workflow",       // Topic name (required)
    description = "...",         // Description (optional)
    partitions = 3,              // Partitions (auto-creation)
    replication = 1              // Replication factor
)
```

## Error Handling

Non-retryable exceptions (like `IllegalArgumentException`) send the message directly to the DLQ.

To customize:

```yaml
stepprflow:
  retry:
    non-retryable-exceptions:
      - java.lang.IllegalArgumentException
      - com.example.BusinessValidationException
```

## Requirements

- Java 21+
- Spring Boot 3.5.x+
- A broker module: `stepprflow-spring-kafka` or `stepprflow-spring-rabbitmq`
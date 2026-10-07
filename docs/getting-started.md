# Getting Started with Steppr Flow

This comprehensive guide will help you set up your first async workflow with Steppr Flow, from project setup to production deployment.

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Project Setup](#project-setup)
3. [Basic Configuration](#basic-configuration)
4. [Creating Your First Workflow](#creating-your-first-workflow)
5. [Starting Workflows](#starting-workflows)
6. [Running the Infrastructure](#running-the-infrastructure)
7. [Testing Your Workflow](#testing-your-workflow)
8. [Adding Monitoring](#adding-monitoring)
9. [Production Considerations](#production-considerations)

---

## Prerequisites

Before starting, ensure you have:

- **Java 21** or later
- **Maven 3.8+** or Gradle 8+
- **Docker & Docker Compose** (for running Kafka, MongoDB)
- An IDE (IntelliJ IDEA, VS Code, etc.)

---

## Project Setup

### Option 1: Add to Existing Spring Boot Project

Add the Steppr Flow starter to your `pom.xml`:

```xml
<properties>
    <stepprflow.version>1.1.1</stepprflow.version>
</properties>

<dependencies>
    <!-- Steppr Flow Core -->
    <dependency>
        <groupId>io.github.stepprflow</groupId>
        <artifactId>stepprflow-core</artifactId>
        <version>${stepprflow.version}</version>
    </dependency>

    <!-- Kafka broker implementation -->
    <dependency>
        <groupId>io.github.stepprflow</groupId>
        <artifactId>stepprflow-spring-kafka</artifactId>
        <version>${stepprflow.version}</version>
    </dependency>
</dependencies>
```

### Option 2: Use RabbitMQ Instead of Kafka

```xml
<dependencies>
    <!-- Steppr Flow Core -->
    <dependency>
        <groupId>io.github.stepprflow</groupId>
        <artifactId>stepprflow-core</artifactId>
        <version>${stepprflow.version}</version>
    </dependency>

    <!-- RabbitMQ broker implementation -->
    <dependency>
        <groupId>io.github.stepprflow</groupId>
        <artifactId>stepprflow-spring-rabbitmq</artifactId>
        <version>${stepprflow.version}</version>
    </dependency>
</dependencies>
```

---

## Basic Configuration

Create or update `src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: my-stepprflow-app

# Steppr Flow Configuration
stepprflow:
  # Enable the framework
  enabled: true

  # Kafka settings
  kafka:
    bootstrap-servers: localhost:9092
    auto-create-topics: true

    consumer:
      group-id: ${spring.application.name}-workers
      concurrency: 3
      auto-offset-reset: earliest

    producer:
      acks: all
      retries: 3
      batch-size: 16384
      linger-ms: 5

    # Security: specify trusted packages for deserialization
    trusted-packages:
      - io.github.stepprflow.core.model
      - com.mycompany.myapp.payload

  # Retry configuration
  retry:
    max-attempts: 3
    initial-delay: 1s
    max-delay: 5m
    multiplier: 2.0
    non-retryable-exceptions:
      - java.lang.IllegalArgumentException
      - java.lang.NullPointerException

  # Dead Letter Queue
  dlq:
    enabled: true
    suffix: .dlq

  # Circuit breaker for broker failures
  circuit-breaker:
    enabled: true
    failure-rate-threshold: 50
    wait-duration-in-open-state: 30s

# Logging
logging:
  level:
    io.github.stepprflow: DEBUG
```

---

## Creating Your First Workflow

### Step 1: Define Your Payload

The payload is the data that flows through your workflow. Create a simple POJO:

```java
package com.mycompany.myapp.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderPayload {

    private String orderId;
    private String customerId;
    private String customerEmail;
    private List<OrderItem> items;
    private BigDecimal totalAmount;
    private PaymentInfo paymentInfo;
    private ShippingAddress shippingAddress;

    // Workflow state - can be modified during execution
    private String paymentTransactionId;
    private String trackingNumber;
    private OrderStatus status;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderItem {
        private String productId;
        private String productName;
        private int quantity;
        private BigDecimal unitPrice;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentInfo {
        private String cardLastFour;
        private String cardType;
        private String paymentToken;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShippingAddress {
        private String street;
        private String city;
        private String state;
        private String zipCode;
        private String country;
    }

    public enum OrderStatus {
        PENDING, VALIDATED, PAID, SHIPPED, COMPLETED, FAILED, CANCELLED
    }
}
```

### Step 2: Create the Workflow Class

Create a class that implements `StepprFlow` and annotate it with `@Topic`:

```java
package com.mycompany.myapp.workflow;

import com.mycompany.myapp.payload.OrderPayload;
import com.mycompany.myapp.payload.OrderPayload.OrderStatus;
import com.mycompany.myapp.service.InventoryService;
import com.mycompany.myapp.service.NotificationService;
import com.mycompany.myapp.service.PaymentService;
import com.mycompany.myapp.service.ShippingService;
import io.github.stepprflow.core.service.StepprFlow;
import io.github.stepprflow.core.annotation.OnFailure;
import io.github.stepprflow.core.annotation.OnSuccess;
import io.github.stepprflow.core.annotation.Step;
import io.github.stepprflow.core.annotation.Timeout;
import io.github.stepprflow.core.annotation.Topic;
import io.github.stepprflow.core.model.WorkflowMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
@Topic(
    value = "order-workflow",
    description = "End-to-end order processing workflow",
    partitions = 3,
    replication = 1
)
public class OrderWorkflow implements StepprFlow {

    private final InventoryService inventoryService;
    private final PaymentService paymentService;
    private final ShippingService shippingService;
    private final NotificationService notificationService;

    /**
     * Step 1: Validate the order data
     * This step validates business rules before processing.
     */
    @Step(id = 1, label = "Validate Order", description = "Validates order data and business rules")
    public void validateOrder(OrderPayload payload) {
        log.info("Validating order: {}", payload.getOrderId());

        // Validate items
        if (payload.getItems() == null || payload.getItems().isEmpty()) {
            throw new IllegalArgumentException("Order must contain at least one item");
        }

        // Validate amount
        if (payload.getTotalAmount() == null ||
            payload.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Total amount must be positive");
        }

        // Validate shipping address
        if (payload.getShippingAddress() == null) {
            throw new IllegalArgumentException("Shipping address is required");
        }

        payload.setStatus(OrderStatus.VALIDATED);
        log.info("Order {} validated successfully", payload.getOrderId());
    }

    /**
     * Step 2: Reserve inventory for the order items
     */
    @Step(id = 2, label = "Reserve Inventory", description = "Reserves inventory for order items")
    public void reserveInventory(OrderPayload payload) {
        log.info("Reserving inventory for order: {}", payload.getOrderId());

        for (OrderPayload.OrderItem item : payload.getItems()) {
            boolean reserved = inventoryService.reserve(
                item.getProductId(),
                item.getQuantity()
            );

            if (!reserved) {
                throw new RuntimeException(
                    "Insufficient inventory for product: " + item.getProductId()
                );
            }
        }

        log.info("Inventory reserved for order: {}", payload.getOrderId());
    }

    /**
     * Step 3: Process payment
     * This step has a timeout of 30 seconds.
     */
    @Step(id = 3, label = "Process Payment", description = "Charges the customer payment method")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void processPayment(OrderPayload payload) {
        log.info("Processing payment for order: {}", payload.getOrderId());

        String transactionId = paymentService.charge(
            payload.getPaymentInfo().getPaymentToken(),
            payload.getTotalAmount(),
            payload.getOrderId()
        );

        // Store transaction ID in payload for tracking
        payload.setPaymentTransactionId(transactionId);
        payload.setStatus(OrderStatus.PAID);

        log.info("Payment processed for order: {}, transactionId: {}",
            payload.getOrderId(), transactionId);
    }

    /**
     * Step 4: Create shipment
     */
    @Step(id = 4, label = "Create Shipment", description = "Creates shipping label and schedules pickup")
    public void createShipment(OrderPayload payload) {
        log.info("Creating shipment for order: {}", payload.getOrderId());

        String trackingNumber = shippingService.createShipment(
            payload.getOrderId(),
            payload.getItems(),
            payload.getShippingAddress()
        );

        payload.setTrackingNumber(trackingNumber);
        payload.setStatus(OrderStatus.SHIPPED);

        log.info("Shipment created for order: {}, tracking: {}",
            payload.getOrderId(), trackingNumber);
    }

    /**
     * Step 5: Send confirmation email
     * This step continues even if it fails (non-critical).
     */
    @Step(
        id = 5,
        label = "Send Confirmation",
        description = "Sends order confirmation email to customer",
        continueOnFailure = true  // Email failure shouldn't fail the workflow
    )
    public void sendConfirmation(OrderPayload payload) {
        log.info("Sending confirmation for order: {}", payload.getOrderId());

        notificationService.sendOrderConfirmation(
            payload.getCustomerEmail(),
            payload.getOrderId(),
            payload.getTrackingNumber()
        );

        log.info("Confirmation sent for order: {}", payload.getOrderId());
    }

    /**
     * Called when workflow completes successfully.
     */
    @OnSuccess
    public void onWorkflowComplete(WorkflowMessage message) {
        log.info("Order workflow completed successfully: executionId={}, correlationId={}",
            message.getExecutionId(), message.getCorrelationId());

        // Access the final payload if needed
        OrderPayload payload = message.getPayloadAs(OrderPayload.class);
        payload.setStatus(OrderStatus.COMPLETED);

        // Emit success metrics, events, etc.
    }

    /**
     * Called when workflow fails after all retries.
     */
    @OnFailure
    public void onWorkflowFailed(WorkflowMessage message) {
        log.error("Order workflow failed: executionId={}, step={}, error={}",
            message.getExecutionId(),
            message.getCurrentStep(),
            message.getErrorInfo() != null ? message.getErrorInfo().getMessage() : "Unknown");

        // Compensation logic
        OrderPayload payload = message.getPayloadAs(OrderPayload.class);

        // Release inventory if reserved
        if (message.getCurrentStep() > 2) {
            compensateInventory(payload);
        }

        // Refund payment if charged
        if (message.getCurrentStep() > 3 && payload.getPaymentTransactionId() != null) {
            compensatePayment(payload);
        }

        payload.setStatus(OrderStatus.FAILED);
    }

    private void compensateInventory(OrderPayload payload) {
        log.info("Releasing inventory for failed order: {}", payload.getOrderId());
        for (OrderPayload.OrderItem item : payload.getItems()) {
            inventoryService.release(item.getProductId(), item.getQuantity());
        }
    }

    private void compensatePayment(OrderPayload payload) {
        log.info("Refunding payment for failed order: {}", payload.getOrderId());
        paymentService.refund(payload.getPaymentTransactionId());
    }
}
```

---

## Starting Workflows

### Using WorkflowStarter

Inject `WorkflowStarter` to start workflows from your controllers or services:

```java
package com.mycompany.myapp.controller;

import com.mycompany.myapp.payload.OrderPayload;
import io.github.stepprflow.core.service.WorkflowStarter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final WorkflowStarter workflowStarter;

    /**
     * Create a new order - starts the workflow
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> createOrder(@RequestBody CreateOrderRequest request) {

        // Build the payload
        OrderPayload payload = OrderPayload.builder()
            .orderId(UUID.randomUUID().toString())
            .customerId(request.getCustomerId())
            .customerEmail(request.getEmail())
            .items(request.getItems())
            .totalAmount(request.calculateTotal())
            .paymentInfo(request.getPaymentInfo())
            .shippingAddress(request.getShippingAddress())
            .status(OrderPayload.OrderStatus.PENDING)
            .build();

        // Start the workflow - returns immediately
        String executionId = workflowStarter.start("order-workflow", payload);

        return ResponseEntity.accepted()
            .body(Map.of(
                "orderId", payload.getOrderId(),
                "executionId", executionId,
                "status", "PROCESSING"
            ));
    }

    /**
     * Start workflow with correlation ID for tracing
     */
    @PostMapping("/with-correlation")
    public ResponseEntity<Map<String, String>> createOrderWithCorrelation(
            @RequestBody CreateOrderRequest request,
            @RequestHeader("X-Correlation-ID") String correlationId) {

        OrderPayload payload = buildPayload(request);

        // Start with a custom correlation id passed as workflow metadata.
        // The 3-arg overload is start(topic, payload, Map<String,Object> metadata).
        String executionId = workflowStarter.start(
                "order-workflow", payload, Map.of("correlationId", correlationId));

        return ResponseEntity.accepted()
            .body(Map.of(
                "orderId", payload.getOrderId(),
                "executionId", executionId,
                "correlationId", correlationId
            ));
    }

    /**
     * Start workflow asynchronously
     */
    @PostMapping("/async")
    public ResponseEntity<Map<String, String>> createOrderAsync(@RequestBody CreateOrderRequest request) {

        OrderPayload payload = buildPayload(request);

        // Non-blocking start
        workflowStarter.startAsync("order-workflow", payload)
            .thenAccept(executionId -> {
                log.info("Workflow started asynchronously: {}", executionId);
            })
            .exceptionally(error -> {
                log.error("Failed to start workflow", error);
                return null;
            });

        return ResponseEntity.accepted()
            .body(Map.of("status", "QUEUED"));
    }

    /**
     * Resume a failed workflow
     */
    @PostMapping("/{executionId}/resume")
    public ResponseEntity<Void> resumeOrder(
            @PathVariable String executionId,
            @RequestParam(required = false) Integer fromStep) {

        workflowStarter.resume(executionId, fromStep);
        return ResponseEntity.accepted().build();
    }

    /**
     * Cancel a running workflow
     */
    @DeleteMapping("/{executionId}")
    public ResponseEntity<Void> cancelOrder(@PathVariable String executionId) {
        workflowStarter.cancel(executionId);
        return ResponseEntity.noContent().build();
    }

    private OrderPayload buildPayload(CreateOrderRequest request) {
        return OrderPayload.builder()
            .orderId(UUID.randomUUID().toString())
            .customerId(request.getCustomerId())
            .customerEmail(request.getEmail())
            .items(request.getItems())
            .totalAmount(request.calculateTotal())
            .paymentInfo(request.getPaymentInfo())
            .shippingAddress(request.getShippingAddress())
            .status(OrderPayload.OrderStatus.PENDING)
            .build();
    }
}
```

---

## Running the Infrastructure

### Docker Compose for Development

Create `docker-compose.yml`:

```yaml
services:
  # Zookeeper for Kafka
  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    container_name: zookeeper
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
    healthcheck:
      test: ["CMD", "nc", "-z", "localhost", "2181"]
      interval: 10s
      timeout: 5s
      retries: 5

  # Apache Kafka
  kafka:
    image: confluentinc/cp-kafka:7.5.0
    container_name: kafka
    depends_on:
      zookeeper:
        condition: service_healthy
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092,INTERNAL://kafka:29092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,INTERNAL:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
    healthcheck:
      test: ["CMD", "kafka-broker-api-versions", "--bootstrap-server", "localhost:9092"]
      interval: 10s
      timeout: 10s
      retries: 10

  # MongoDB for monitoring
  mongodb:
    image: mongo:7.0
    container_name: mongodb
    ports:
      - "27017:27017"
    volumes:
      - mongodb_data:/data/db
    healthcheck:
      test: ["CMD", "mongosh", "--eval", "db.adminCommand('ping')"]
      interval: 10s
      timeout: 5s
      retries: 5

  # Kafka UI (optional, for debugging)
  kafka-ui:
    image: provectuslabs/kafka-ui:latest
    container_name: kafka-ui
    ports:
      - "8081:8080"
    environment:
      KAFKA_CLUSTERS_0_NAME: local
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:29092
    depends_on:
      kafka:
        condition: service_healthy

  # Mongo Express (optional, for debugging)
  mongo-express:
    image: mongo-express:latest
    container_name: mongo-express
    ports:
      - "8082:8081"
    environment:
      ME_CONFIG_MONGODB_URL: mongodb://mongodb:27017/
      ME_CONFIG_BASICAUTH: "false"
    depends_on:
      mongodb:
        condition: service_healthy

volumes:
  mongodb_data:
```

Start the infrastructure:

```bash
docker-compose up -d
```

Verify services are running:

```bash
docker-compose ps
```

---

## Testing Your Workflow

### 1. Start Your Application

```bash
mvn spring-boot:run
```

### 2. Create an Order

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "cust-123",
    "email": "customer@example.com",
    "items": [
      {
        "productId": "prod-001",
        "productName": "Widget",
        "quantity": 2,
        "unitPrice": 29.99
      }
    ],
    "paymentInfo": {
      "cardLastFour": "4242",
      "cardType": "VISA",
      "paymentToken": "tok_visa"
    },
    "shippingAddress": {
      "street": "123 Main St",
      "city": "Springfield",
      "state": "IL",
      "zipCode": "62701",
      "country": "USA"
    }
  }'
```

### 3. Expected Response

```json
{
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "executionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "PROCESSING"
}
```

### 4. Check Logs

You should see logs like:

```
INFO  [order-workflow] Validating order: 550e8400-e29b-41d4-a716-446655440000
INFO  [order-workflow] Order 550e8400-e29b-41d4-a716-446655440000 validated successfully
INFO  [order-workflow] Reserving inventory for order: 550e8400-e29b-41d4-a716-446655440000
INFO  [order-workflow] Inventory reserved for order: 550e8400-e29b-41d4-a716-446655440000
INFO  [order-workflow] Processing payment for order: 550e8400-e29b-41d4-a716-446655440000
INFO  [order-workflow] Payment processed for order: 550e8400-e29b-41d4-a716-446655440000
INFO  [order-workflow] Creating shipment for order: 550e8400-e29b-41d4-a716-446655440000
INFO  [order-workflow] Shipment created for order: 550e8400-e29b-41d4-a716-446655440000
INFO  [order-workflow] Sending confirmation for order: 550e8400-e29b-41d4-a716-446655440000
INFO  [order-workflow] Confirmation sent for order: 550e8400-e29b-41d4-a716-446655440000
INFO  [order-workflow] Order workflow completed successfully: executionId=a1b2c3d4-...
```

---

## Adding Monitoring

### How Registration Works

Services **automatically register** their workflow definitions with the monitoring dashboard through the shared message broker (Kafka or RabbitMQ). There is no server URL to configure and no HTTP-based registration endpoint. As long as your application and the monitoring server share the same broker, registration is zero-config:

1. On startup, each service publishes its workflow definitions to the `stepprflow.registration` broker topic.
2. The monitoring server consumes these messages and tracks registered services.
3. Periodic heartbeats keep the registration alive; a deregistration message is sent on shutdown.

Registration is enabled by default. To disable it, set:

```yaml
stepprflow:
  registration:
    enabled: false
```

### Deploy the Dashboard

Deploy the standalone monitoring server or embed it in your application. It only needs access to the same broker and MongoDB.

**Option A: Standalone Dashboard (Recommended for Production)**

```bash
# Build from the project root
mvn package -pl stepprflow-monitoring -am -DskipTests

# Run with Docker
docker build -t stepprflow-monitoring .
docker run -d \
  -p 8090:8090 \
  -e MONGODB_URI=mongodb://host.docker.internal:27017/stepprflow \
  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \
  -e STEPPRFLOW_MONITOR_AUTH_MODE=basic \
  -e STEPPRFLOW_MONITOR_AUTH_BASIC_USERNAME=admin \
  -e STEPPRFLOW_MONITOR_AUTH_BASIC_PASSWORD=change-me \
  -e STEPPRFLOW_MONITOR_AUTH_BASIC_ROLE=OPERATOR \
  stepprflow-monitoring
```

Once the monitoring server is running and connected to the same broker as your services, all workflow definitions will appear automatically -- no additional configuration required on either side.

**Option B: Add to Your Application**

```xml
<dependency>
    <groupId>io.github.stepprflow</groupId>
    <artifactId>stepprflow-monitoring</artifactId>
    <version>${stepprflow.version}</version>
</dependency>
```

### Access the Dashboard

Open http://localhost:8090 in your browser to:

- View all workflow executions in real-time
- Filter by topic, status, service name
- View step-by-step execution details
- Resume failed workflows
- Cancel running workflows
- Modify payloads for recovery scenarios

---

## Production Considerations

### 1. Kafka Configuration

```yaml
stepprflow:
  kafka:
    bootstrap-servers: kafka1:9092,kafka2:9092,kafka3:9092
    consumer:
      concurrency: 6  # Match partition count
    producer:
      acks: all
      retries: 10
```

### 2. Security

```yaml
stepprflow:
  kafka:
    # Only trust your own packages
    trusted-packages:
      - io.github.stepprflow.core.model
      - com.mycompany.myapp.payload
```

### 3. Monitoring & Alerting

- Enable circuit breaker metrics
- Set up alerts on DLQ topic
- Monitor consumer lag

### 4. High Availability

- Deploy multiple instances of your application
- Use Kafka partition count = consumer count
- Configure MongoDB replica set

---

## Next Steps

- [Configure Message Brokers](./brokers.md) - Kafka & RabbitMQ deep dive
- [Security Configuration](./security.md) - Authentication & authorization
- [Monitoring & Dashboard](./monitoring.md) - Setting up the monitoring dashboard
- [Modules](./modules.md) - Detailed module descriptions
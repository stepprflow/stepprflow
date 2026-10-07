# Monitoring & Dashboard

Steppr Flow provides comprehensive monitoring capabilities through the **stepprflow-monitoring** module, which includes:
- Embeddable monitoring library for your Spring Boot applications
- Standalone monitoring server with Vue.js UI
- Workflow registry with broker-based service discovery

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                     Your Application                             │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │             stepprflow-core (registration)               │    │
│  │  • Auto-registers workflows via message broker           │    │
│  │  • Sends REGISTER / HEARTBEAT / DEREGISTER messages      │    │
│  │  • Uses stepprflow.registration topic                    │    │
│  └──────────────────────────────────────────────────────────┘    │
└──────────────────────┬───────────────────────────────────────────┘
                       │ Message Broker (Kafka / RabbitMQ)
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│                 stepprflow-monitoring                            │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  • Execution persistence (MongoDB)                      │    │
│  │  • REST API endpoints                                   │    │
│  │  • WebSocket broadcasts                                 │    │
│  │  • Retry scheduling                                     │    │
│  │  • Metrics collection                                   │    │
│  │  • Broker message listener                              │    │
│  │  • Workflow registry (broker-based)                      │    │
│  │  • Vue.js UI (stepprflow-ui)                            │    │
│  └─────────────────────────────────────────────────────────┘    │
│                           │                                     │
│                           ▼                                     │
│                     ┌──────────┐                                │
│                     │ Browser  │                                │
│                     └──────────┘                                │
└─────────────────────────────────────────────────────────────────┘
```

---

## stepprflow-monitoring

This module provides monitoring capabilities, a workflow registry, and a built-in Vue.js dashboard UI.

### Dependencies

```xml
<dependency>
    <groupId>io.github.stepprflow</groupId>
    <artifactId>stepprflow-monitoring</artifactId>
    <version>${stepprflow.version}</version>
</dependency>
```

### Configuration

```yaml
stepprflow:
  monitor:
    enabled: true                              # Enable monitoring module
    collection-name: workflow_executions       # MongoDB collection name

    # WebSocket configuration
    web-socket:
      enabled: true
      endpoint: /ws/workflow                   # WebSocket endpoint
      topic-prefix: /topic/workflow            # STOMP topic prefix

    # Data retention
    retention:
      completed-ttl: 7d                        # Keep completed executions for 7 days
      failed-ttl: 30d                          # Keep failed executions for 30 days

    # Automatic retry scheduler
    retry-scheduler:
      enabled: true
      check-interval: 30s                      # Check for pending retries every 30s

    # Workflow registry for multi-instance deployments
    registry:
      instance-timeout: 90s                    # Mark instances stale after 90 seconds
      cleanup-interval: 30s                    # Run cleanup every 30 seconds

    # MongoDB connection — the monitor builds its OWN MongoClient from this
    # property (NOT spring.data.mongodb.uri). The MONGODB_URI env var is
    # accepted as a convenience alias.
    mongodb:
      uri: mongodb://localhost:27017/stepprflow

    # Authentication (SF-5) — REQUIRED. With no mode set, the dashboard denies
    # every request (fail-closed) except /actuator/health. Pick basic or oidc.
    auth:
      mode: basic                              # or: oidc
      basic:
        username: admin
        password: "{bcrypt}$2a$10$..."         # bcrypt hash (plaintext accepted but logged as a warning)
        role: OPERATOR                          # OPERATOR (can mutate) or VIEWER (read-only)
```

---

## REST API

The monitor module exposes REST endpoints for workflow management.

### Workflows API

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/workflows` | List executions with pagination and filtering |
| `GET` | `/api/workflows/{id}` | Get execution details |
| `GET` | `/api/workflows/recent` | Get 10 most recent executions |
| `GET` | `/api/workflows/stats` | Get aggregated statistics |
| `POST` | `/api/workflows/{id}/resume` | Resume failed execution |
| `DELETE` | `/api/workflows/{id}` | Cancel running execution |
| `PATCH` | `/api/workflows/{id}/payload` | Update payload field |
| `POST` | `/api/workflows/{id}/payload/restore` | Restore original payload |

### Query Parameters for List Executions

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `topic` | string | - | Filter by workflow topic |
| `statuses` | string | - | Filter by status (comma-separated: PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED, RETRY_PENDING, TIMED_OUT, PAUSED, SKIPPED, PASSED) |
| `page` | int | 0 | Page number (0-based) |
| `size` | int | 20 | Page size (1-100) |
| `sortBy` | string | createdAt | Sort field (createdAt, updatedAt, status, topic, currentStep) |
| `direction` | string | DESC | Sort direction (ASC, DESC) |

### Example: List Failed Executions

```bash
curl "http://localhost:8090/api/workflows?statuses=FAILED&size=50&sortBy=updatedAt"
```

### Example: Resume with Modified Payload

```bash
# Update incorrect email in payload
curl -X PATCH "http://localhost:8090/api/workflows/exec-123/payload" \
  -H "Content-Type: application/json" \
  -d '{
    "fieldPath": "customer.email",
    "newValue": "correct@email.com",
    "changedBy": "admin",
    "reason": "Correcting typo in customer email"
  }'

# Resume execution
curl -X POST "http://localhost:8090/api/workflows/exec-123/resume"
```

---

## Metrics API

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/metrics` | Get metrics dashboard (global + per workflow) |
| `GET` | `/api/metrics/{topic}` | Get metrics for specific workflow |
| `GET` | `/api/metrics/summary` | Get global summary |

### Metrics Dashboard Response

```json
{
  "totalStarted": 1500,
  "totalCompleted": 1450,
  "totalFailed": 30,
  "totalCancelled": 5,
  "totalActive": 15,
  "totalRetries": 50,
  "totalDlq": 10,
  "globalSuccessRate": 96.67,
  "globalFailureRate": 2.0,
  "workflowMetrics": [
    {
      "topic": "order-workflow",
      "serviceName": "order-service",
      "started": 1000,
      "completed": 980,
      "failed": 15,
      "cancelled": 2,
      "active": 3,
      "retries": 25,
      "dlq": 5,
      "avgDurationMs": 2500,
      "successRate": 98.0
    }
  ]
}
```

---

## Circuit Breaker API

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/circuit-breakers` | List all circuit breakers |
| `GET` | `/api/circuit-breakers/{name}` | Get specific circuit breaker status |
| `GET` | `/api/circuit-breakers/config` | Get circuit breaker configuration |
| `POST` | `/api/circuit-breakers/{name}/reset` | Reset circuit breaker to CLOSED |

### Circuit Breaker Status Response

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

---

## WebSocket Real-time Updates

The monitor broadcasts workflow updates via WebSocket using STOMP protocol.

### Topics

| Topic | Description |
|-------|-------------|
| `/topic/workflow/updates` | All workflow updates |
| `/topic/workflow/{topic}` | Updates for specific workflow topic |
| `/topic/workflow/execution/{id}` | Updates for specific execution (terminal states only) |

### Connecting with JavaScript

```javascript
import { Client } from '@stomp/stompjs';

const client = new Client({
  brokerURL: 'ws://localhost:8090/ws/workflow',
  onConnect: () => {
    // Subscribe to all updates
    client.subscribe('/topic/workflow/updates', (message) => {
      const update = JSON.parse(message.body);
      console.log('Workflow update:', update);
    });

    // Subscribe to specific workflow
    client.subscribe('/topic/workflow/order-workflow', (message) => {
      const update = JSON.parse(message.body);
      console.log('Order workflow update:', update);
    });
  }
});

client.activate();
```

### WebSocket Message Format

```json
{
  "executionId": "exec-123",
  "topic": "order-workflow",
  "status": "IN_PROGRESS",
  "currentStep": 2,
  "totalSteps": 5,
  "updatedAt": "2024-01-15T10:30:00Z",
  "durationMs": 1500
}
```

---

## Running the Monitoring Dashboard

The `stepprflow-monitoring` module includes a built-in Vue.js dashboard UI.

### With Docker Compose

```yaml
services:
  stepprflow-monitoring:
    build:
      context: .
      dockerfile: Dockerfile
    ports:
      - "8090:8090"
    environment:
      - MONGODB_URI=mongodb://mongo:27017/stepprflow
      - KAFKA_BOOTSTRAP_SERVERS=kafka:9092
      # Authentication is REQUIRED — the dashboard is fail-closed without it.
      - STEPPRFLOW_MONITOR_AUTH_MODE=basic
      - STEPPRFLOW_MONITOR_AUTH_BASIC_USERNAME=admin
      - STEPPRFLOW_MONITOR_AUTH_BASIC_PASSWORD=change-me
      - STEPPRFLOW_MONITOR_AUTH_BASIC_ROLE=OPERATOR
    depends_on:
      - mongo
      - kafka
```

### Standalone JAR

```bash
java -jar stepprflow-monitoring.jar \
  --stepprflow.monitor.mongodb.uri=mongodb://localhost:27017/stepprflow \
  --stepprflow.kafka.bootstrap-servers=localhost:9092 \
  --stepprflow.monitor.auth.mode=basic \
  --stepprflow.monitor.auth.basic.username=admin \
  --stepprflow.monitor.auth.basic.password=change-me \
  --stepprflow.monitor.auth.basic.role=OPERATOR
```

### Dashboard Configuration

```yaml
server:
  port: 8090

stepprflow:
  monitor:
    # The monitor reads its Mongo URI here, not from spring.data.mongodb.uri.
    mongodb:
      uri: mongodb://localhost:27017/stepprflow
    # Authentication is REQUIRED — without a mode the dashboard is fail-closed.
    auth:
      mode: basic                              # or: oidc
      basic:
        username: admin
        password: "{bcrypt}$2a$10$..."
        role: OPERATOR
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: stepprflow-monitoring

  # UI configuration
  ui:
    enabled: true
    base-path: /                               # UI base path
```

---

## Dashboard UI Features

### Workflows View

- **Real-time updates** via WebSocket
- **Filtering** by topic and status
- **Pagination** with configurable page size
- **Sorting** by date, status, or topic
- **Quick actions**: Resume, Cancel, View details

### Execution Details

- **Step-by-step progress** visualization
- **Payload inspection** with JSON viewer
- **Payload modification** for failed executions
- **Error information** and stack traces
- **Audit trail** of payload changes

### Metrics View

- **Global statistics**: Success rate, failure rate, active workflows
- **Per-workflow breakdown** with charts
- **Circuit breaker status** and controls

---

## Payload Modification

One of the most powerful features is the ability to modify payloads for failed executions before resuming.

### Use Cases

1. **Correct input errors**: Fix typos, invalid data
2. **Update external references**: Change IDs, URLs
3. **Adjust configuration**: Modify flags, thresholds
4. **Test scenarios**: Inject different values for debugging

### How It Works

1. Execution fails (status: `FAILED`, `PAUSED`, or `RETRY_PENDING`)
2. Original payload is preserved
3. Admin modifies specific fields via API or UI
4. Changes are tracked with audit information
5. Execution is resumed with modified payload
6. If needed, payload can be restored to original

### Payload Change Tracking

Each modification is tracked:

```json
{
  "executionId": "exec-123",
  "payload": { "customer": { "email": "new@email.com" } },
  "originalPayload": { "customer": { "email": "old@email.com" } },
  "payloadModified": true,
  "payloadChanges": [
    {
      "fieldPath": "customer.email",
      "oldValue": "old@email.com",
      "newValue": "new@email.com",
      "changedAt": "2024-01-15T10:30:00Z",
      "changedBy": "admin",
      "reason": "Customer provided correct email"
    }
  ]
}
```

---

## Workflow Registry

For multi-instance deployments, Steppr Flow maintains a registry of active workflow instances. Registration is performed automatically via the message broker -- no HTTP-based registration or `stepprflow.agent.server-url` configuration is needed.

### How It Works

When a `MessageBroker` bean is present in the application context, the `RegistrationAutoConfiguration` activates and creates a `WorkflowRegistrationClient`. This client:

1. **On startup** (after a 5-second delay): collects all workflow definitions from the local `WorkflowRegistry` and sends a `REGISTER` message on the `stepprflow.registration` topic via the broker.
2. **Periodically**: sends `HEARTBEAT` messages at a configurable interval to indicate the instance is alive.
3. **On shutdown**: sends a `DEREGISTER` message so the monitoring dashboard can immediately remove the instance.

The `stepprflow-monitoring` module listens on the `stepprflow.registration` topic and maintains the registry in MongoDB.

### Service-Side Configuration (stepprflow-core)

These properties control the registration client in your workflow application:

```yaml
stepprflow:
  registration:
    enabled: true                              # Enable/disable registration (default: true)
    heartbeat-interval-seconds: 30             # Heartbeat interval in seconds (default: 30)
```

Registration is automatic when:
- A `MessageBroker` bean is present (e.g., from `stepprflow-spring-kafka` or `stepprflow-spring-rabbitmq`)
- `stepprflow.registration.enabled` is `true` (the default)

### Monitoring-Side Configuration (stepprflow-monitoring)

These properties control how the monitoring dashboard manages the registry:

```yaml
stepprflow:
  monitor:
    registry:
      instance-timeout: 90s                    # Mark instances stale after this duration (default: 90s)
      cleanup-interval: 30s                    # How often to check for stale instances (default: 30s)
```

### Features

- **Auto-registration** via broker when application starts (no HTTP calls needed)
- **Heartbeat** to keep instance alive
- **Stale instance cleanup** after timeout
- **Step metadata** for all registered workflows
- **Automatic reactivation** when a previously inactive workflow re-registers

### Registry API

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/registry/workflows` | List all registered workflows |
| `GET` | `/api/registry/workflows/{topic}` | Get specific workflow registration |
| `GET` | `/api/registry/instances` | List all active instances |

---

## Micrometer Integration

Steppr Flow exposes metrics via Micrometer for integration with monitoring systems.

### Available Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `stepprflow.workflows.started` | Counter | Total workflows started |
| `stepprflow.workflows.completed` | Counter | Total workflows completed |
| `stepprflow.workflows.failed` | Counter | Total workflows failed |
| `stepprflow.workflows.cancelled` | Counter | Total workflows cancelled |
| `stepprflow.workflows.active` | Gauge | Currently active workflows |
| `stepprflow.workflows.duration` | Timer | Workflow execution duration |
| `stepprflow.steps.duration` | Timer | Step execution duration |
| `stepprflow.retries` | Counter | Total retry attempts |
| `stepprflow.dlq` | Counter | Messages sent to DLQ |

### Prometheus Export

Add Spring Boot Actuator with Prometheus:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

Configure endpoint:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  endpoint:
    prometheus:
      enabled: true
```

Access metrics at `/actuator/prometheus`.

---

## OpenAPI Documentation

The monitor module includes OpenAPI (Swagger) documentation.

### Enabling Swagger UI

Add SpringDoc dependency:

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.3.0</version>
</dependency>
```

Access Swagger UI at `/swagger-ui.html`.

### API Documentation Endpoints

| Endpoint | Description |
|----------|-------------|
| `/swagger-ui.html` | Swagger UI |
| `/v3/api-docs` | OpenAPI JSON |
| `/v3/api-docs.yaml` | OpenAPI YAML |

---

## Production Recommendations

### MongoDB

1. **Use replica set** for high availability
2. **Create indexes** on frequently queried fields:
   ```javascript
   db.workflow_executions.createIndex({ "status": 1, "createdAt": -1 })
   db.workflow_executions.createIndex({ "topic": 1, "status": 1 })
   db.workflow_executions.createIndex({ "executionId": 1 }, { unique: true })
   ```
3. **Configure TTL indexes** for automatic cleanup (optional)

### WebSocket

1. **Use sticky sessions** if behind load balancer
2. **Consider WebSocket-compatible load balancer** (AWS ALB, nginx with upgrade)
3. **Monitor connection count** to prevent resource exhaustion

### Dashboard

1. **Secure with authentication** (Spring Security, OAuth2)
2. **Use HTTPS** in production
3. **Configure CORS** appropriately for your deployment

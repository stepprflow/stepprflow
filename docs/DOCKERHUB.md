# Steppr Flow — Monitor

Monitoring **dashboard, REST API and WebSocket** for
[Steppr Flow](https://github.com/stepprflow/stepprflow), a multi-broker workflow
orchestration framework for Spring Boot (Kafka / RabbitMQ).

This image is the **optional** monitor: it observes running workflows (started,
in-progress, completed, failed, retried, DLQ'd), persists execution state in
MongoDB, and serves a dashboard + API. The framework itself is a set of Java
libraries on [Maven Central](https://central.sonatype.com/artifact/io.github.stepprflow/stepprflow-core)
— you do **not** need this image to use Steppr Flow.

🌐 **Website:** https://stepprflow.github.io/stepprflow/
📦 **Source & docs:** https://github.com/stepprflow/stepprflow

---

## Tags

| Tag | Notes |
|-----|-------|
| `1.1.0` | The release version — each release is published under its own version tag |
| `latest` | Points to the most recent release |

> For a reproducible deployment, pin by digest (`@sha256:…`) rather than a tag.

**Architectures:** `linux/amd64`, `linux/arm64`.
Also published to GHCR: `ghcr.io/stepprflow/stepprflow-monitor`.

---

## Quick start

The monitor needs **MongoDB** (persistence) and your **broker** (Kafka by
default). Authentication is **fail-closed**: until you pick a mode, every request
is denied — so set `STEPPRFLOW_MONITOR_AUTH_MODE`.

```bash
docker run -d --name stepprflow-monitor \
  -p 8090:8090 \
  -e MONGODB_URI="mongodb://mongo:27017/stepprflow" \
  -e KAFKA_BOOTSTRAP_SERVERS="kafka:9092" \
  -e STEPPRFLOW_MONITOR_AUTH_MODE="basic" \
  -e STEPPRFLOW_MONITOR_AUTH_BASIC_USERNAME="admin" \
  -e STEPPRFLOW_MONITOR_AUTH_BASIC_PASSWORD="change-me" \
  -e STEPPRFLOW_MONITOR_AUTH_BASIC_ROLE="OPERATOR" \
  alimhin/stepprflow-monitor:1.1.0
```

Then open **http://localhost:8090** (dashboard) — API under `/api`, health at
`/actuator/health`, metrics at `/actuator/prometheus`.

---

## Configuration

| Env var | Default | Description |
|---------|---------|-------------|
| `SERVER_PORT` | `8090` | HTTP port |
| `MONGODB_URI` | `mongodb://localhost:27017/stepprflow` | MongoDB connection string |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka bootstrap servers |
| `STEPPRFLOW_MONITOR_AUTH_MODE` | *(unset → denies all)* | `basic` or `oidc` |
| `STEPPRFLOW_MONITOR_AUTH_BASIC_USERNAME` | `admin` | Local user (basic mode) |
| `STEPPRFLOW_MONITOR_AUTH_BASIC_PASSWORD` | — | bcrypt hash (`{bcrypt}$2a$…`) or plaintext (basic mode) |
| `STEPPRFLOW_MONITOR_AUTH_BASIC_ROLE` | — | `OPERATOR` (can mutate) or `VIEWER` (read-only) |
| `JAVA_OPTS` | container-aware G1 defaults | JVM options |

For **OIDC** (SSO via Keycloak/OIDC) set `STEPPRFLOW_MONITOR_AUTH_MODE=oidc` plus
`spring.security.oauth2.client.*` / `issuer-uri`. See the
[security guide](https://github.com/stepprflow/stepprflow/blob/main/docs/security.md)
and [monitoring guide](https://github.com/stepprflow/stepprflow/blob/main/docs/monitoring.md).

---

## Compose example

```yaml
services:
  mongo:
    image: mongo:7
  monitor:
    image: alimhin/stepprflow-monitor:1.1.0
    ports: ["8090:8090"]
    environment:
      MONGODB_URI: mongodb://mongo:27017/stepprflow
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      STEPPRFLOW_MONITOR_AUTH_MODE: basic
      STEPPRFLOW_MONITOR_AUTH_BASIC_USERNAME: admin
      STEPPRFLOW_MONITOR_AUTH_BASIC_PASSWORD: change-me
      STEPPRFLOW_MONITOR_AUTH_BASIC_ROLE: OPERATOR
    depends_on: [mongo]
```

---

Licensed under **Apache-2.0**. Java 21+ · Spring Boot 3.5+.

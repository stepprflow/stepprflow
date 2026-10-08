# =============================================================================
# Multi-stage build for Steppr Flow Monitoring Dashboard
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 0: Build the SPA on the NATIVE build platform
# -----------------------------------------------------------------------------
# Pinned to $BUILDPLATFORM so node/vite always run natively even for a cross
# (e.g. arm64) image build — running the official glibc Node.js under QEMU
# emulation is slow and occasionally segfaults. The produced bundle is static
# and architecture-independent, so it is simply COPYd into the builder below.
FROM --platform=$BUILDPLATFORM node:22-bookworm-slim@sha256:c3de60bf2f9dd0ac6370e6117950ff62d6e339527e7472301c9c78a017978392 AS ui-builder

WORKDIR /ui
COPY stepprflow-ui/package.json stepprflow-ui/package-lock.json ./
RUN npm ci
COPY stepprflow-ui/ ./
RUN npm run build

# -----------------------------------------------------------------------------
# Stage 1: Build Spring Boot application (backend jar + packaged SPA)
# -----------------------------------------------------------------------------
# glibc base (Debian), NOT Alpine. The SPA is built in the native stage above
# and copied in, so the frontend-maven-plugin is skipped here (-Dfrontend.skip):
# the Maven build only assembles the backend jar and packages the copied bundle.
FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS builder

WORKDIR /app

# Copy pom files first for better caching
COPY pom.xml .
COPY stepprflow-core/pom.xml stepprflow-core/
COPY stepprflow-spring-kafka/pom.xml stepprflow-spring-kafka/
COPY stepprflow-spring-rabbitmq/pom.xml stepprflow-spring-rabbitmq/
COPY stepprflow-idempotency-redis/pom.xml stepprflow-idempotency-redis/
COPY stepprflow-monitoring/pom.xml stepprflow-monitoring/

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -pl stepprflow-monitoring -am -q || true

# Copy source code
COPY stepprflow-core/src stepprflow-core/src
COPY stepprflow-spring-kafka/src stepprflow-spring-kafka/src
COPY stepprflow-spring-rabbitmq/src stepprflow-spring-rabbitmq/src
COPY stepprflow-idempotency-redis/src stepprflow-idempotency-redis/src
COPY stepprflow-monitoring/src stepprflow-monitoring/src

# The SPA bundle built natively in stage 0 — packaged as-is into the jar's
# classpath static resources (frontend-maven-plugin is skipped below).
COPY --from=ui-builder /ui/dist stepprflow-monitoring/src/main/resources/static

# Copy checkstyle config
COPY config/checkstyle/checkstyle.xml config/checkstyle/checkstyle.xml

# Build the backend jar and package the copied SPA (frontend build skipped).
RUN mvn clean package -pl stepprflow-monitoring -am -DskipTests -Dfrontend.skip=true -q

# -----------------------------------------------------------------------------
# Stage 2: Extract Spring Boot layers for optimized caching
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine@sha256:0bfc69a4758a86710e5c474032d28400a8bd00874766f9e8b1642ac2fd293159 AS layers

WORKDIR /app
COPY --from=builder /app/stepprflow-monitoring/target/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# -----------------------------------------------------------------------------
# Stage 3: Final runtime image
# -----------------------------------------------------------------------------
# glibc-based (Ubuntu), NOT Alpine: the Kafka producer uses snappy compression
# (KafkaBrokerAutoConfiguration), whose native library (libsnappyjava.so) is a
# glibc build and fails to load on Alpine's musl with
# "Error loading shared library ld-linux-x86-64.so.2". On Alpine amd64 this
# breaks every producer and prevents the dashboard from decompressing workflow
# messages. See the Alpine/snappy incident; the fix is a glibc base.
FROM eclipse-temurin:21-jre@sha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5

LABEL maintainer="Ali M'HIN <alimhin@gmail.com>"
LABEL description="Steppr Flow Monitoring Dashboard"
LABEL org.opencontainers.image.source="https://github.com/stepprflow/stepprflow"
LABEL org.opencontainers.image.title="Steppr Flow Dashboard"
LABEL org.opencontainers.image.description="Multi-broker workflow orchestration monitoring dashboard"
LABEL org.opencontainers.image.vendor="Steppr Flow"

WORKDIR /app

# curl for the container HEALTHCHECK (Ubuntu base ships neither curl nor wget).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user for security. Use a system account (id < 1000): the
# Ubuntu base already ships a user/group at GID/UID 1000.
RUN groupadd --system stepprflow && \
    useradd --system -g stepprflow -s /bin/sh stepprflow

# Copy layers in order of change frequency (less frequent first)
COPY --from=layers /app/dependencies/ ./
COPY --from=layers /app/spring-boot-loader/ ./
COPY --from=layers /app/snapshot-dependencies/ ./
COPY --from=layers /app/application/ ./

# Change ownership
RUN chown -R stepprflow:stepprflow /app

USER stepprflow

# Expose port
EXPOSE 8090

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8090/actuator/health || exit 1

# JVM options for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"

# Default Spring profiles
ENV SPRING_PROFILES_ACTIVE="docker"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]

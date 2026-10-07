# =============================================================================
# Multi-stage build for Steppr Flow Monitoring Dashboard
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: Build Spring Boot application
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21-alpine@sha256:308cba8b638ed7e4658cea3f8399066219466211c805f6d5728c3c9c7614661b AS builder

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

# Copy checkstyle config
COPY config/checkstyle/checkstyle.xml config/checkstyle/checkstyle.xml

# Build the application
RUN mvn clean package -pl stepprflow-monitoring -am -DskipTests -q

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
FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555

LABEL maintainer="Steppr Flow Team <contact@stepprflow.io>"
LABEL description="Steppr Flow Monitoring Dashboard"
LABEL org.opencontainers.image.source="https://github.com/steppr-flow/stepprflow"
LABEL org.opencontainers.image.title="Steppr Flow Dashboard"
LABEL org.opencontainers.image.description="Multi-broker workflow orchestration monitoring dashboard"
LABEL org.opencontainers.image.vendor="Steppr Flow"

WORKDIR /app

# Create non-root user for security
RUN addgroup -g 1000 stepprflow && \
    adduser -u 1000 -G stepprflow -s /bin/sh -D stepprflow

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
    CMD wget --no-verbose --tries=1 --spider http://localhost:8090/actuator/health || exit 1

# JVM options for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"

# Default Spring profiles
ENV SPRING_PROFILES_ACTIVE="docker"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]

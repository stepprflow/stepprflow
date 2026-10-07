# =============================================================================
# Sample application image — used by the end-to-end CI smoke (docker.yml:e2e).
# Runs the OrderWorkflow demo so the full chain (workflow -> broker -> monitor
# -> Mongo -> API) can be exercised against a real producer. Build context is
# the repository root: `docker build -f stepprflow-samples/Dockerfile .`
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: build the executable sample jar (needs the 'full' profile for the
# stepprflow-samples module, plus its reactor dependencies via -am).
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app

# Poms first for dependency-layer caching
COPY pom.xml .
COPY stepprflow-core/pom.xml stepprflow-core/
COPY stepprflow-spring-kafka/pom.xml stepprflow-spring-kafka/
COPY stepprflow-spring-rabbitmq/pom.xml stepprflow-spring-rabbitmq/
COPY stepprflow-idempotency-redis/pom.xml stepprflow-idempotency-redis/
COPY stepprflow-monitoring/pom.xml stepprflow-monitoring/
COPY stepprflow-samples/pom.xml stepprflow-samples/
# The 'full' profile also lists these modules; Maven must be able to read their
# poms even though -am never builds them for the sample.
COPY stepprflow-load-tests/pom.xml stepprflow-load-tests/
COPY stepprflow-ui/pom.xml stepprflow-ui/

RUN mvn -Pfull dependency:go-offline -pl stepprflow-samples -am -q || true

# Sources for the sample and its reactor dependencies
COPY stepprflow-core/src stepprflow-core/src
COPY stepprflow-spring-kafka/src stepprflow-spring-kafka/src
COPY stepprflow-spring-rabbitmq/src stepprflow-spring-rabbitmq/src
COPY stepprflow-samples/src stepprflow-samples/src
COPY config/checkstyle/checkstyle.xml config/checkstyle/checkstyle.xml

# Produces an executable fat jar (repackage is bound in the sample pom)
RUN mvn -Pfull clean package -pl stepprflow-samples -am -DskipTests -q

# -----------------------------------------------------------------------------
# Stage 2: runtime
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
RUN addgroup -g 1000 stepprflow && \
    adduser -u 1000 -G stepprflow -s /bin/sh -D stepprflow

COPY --from=builder /app/stepprflow-samples/target/stepprflow-samples-*.jar app.jar
RUN chown -R stepprflow:stepprflow /app
USER stepprflow

EXPOSE 8010

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"
# Kafka broker profile by default; point it at the broker with
# STEPPRFLOW_KAFKA_BOOTSTRAP_SERVERS.
ENV SPRING_PROFILES_ACTIVE="kafka"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]

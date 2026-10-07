# Development Guide

This guide covers setting up a local development environment for Steppr Flow.

## Prerequisites

- **Java 21** (Temurin/OpenJDK recommended)
- **Maven 3.9+**
- **Node.js 20+** (for UI development)
- **Docker** (for integration tests)
- **Git**

## Quick Start

### 1. Clone the repository

```bash
git clone https://github.com/stepprflow/stepprflow.git
cd stepprflow
```

### 2. Build all modules

```bash
mvn clean install
```

### 3. Run tests

```bash
mvn test
```

## Project Structure

```
stepprflow/
├── .github/                 # GitHub Actions workflows
│   ├── workflows/
│   │   ├── ci.yml          # CI pipeline
│   │   └── release.yml     # Release automation
│   └── dependabot.yml      # Dependency updates
├── config/
│   └── checkstyle/         # Checkstyle configuration
├── docs/                   # Documentation
├── stepprflow-core/       # Core module
├── stepprflow-spring-*/   # Spring integrations
├── stepprflow-ui/         # Vue.js frontend
└── pom.xml                 # Parent POM
```

## Building Individual Modules

Build a specific module:
```bash
mvn -pl stepprflow-core clean install
```

Build with dependencies:
```bash
mvn -pl stepprflow-spring-kafka -am clean install
```

## Running the Sample Applications

### Kafka Sample

1. Start infrastructure:
```bash
docker-compose up -d
```

2. Run the application with the `kafka` profile (the sample module lives in the
   `full` profile):
```bash
mvn -Pfull -pl stepprflow-samples spring-boot:run -Dspring-boot.run.profiles=kafka
```

3. Test the API (the sample runs on port 8010; the body must be a full
   `CreateOrderRequest`):
```bash
curl -X POST http://localhost:8010/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "CUST-1",
    "customerEmail": "customer@example.com",
    "items": [{"productId": "PROD-001", "productName": "Widget", "quantity": 2, "price": 49.99}],
    "payment": {"cardLast4": "4242", "cardType": "VISA"},
    "shipping": {"street": "1 Main St", "city": "Paris", "state": "IDF", "zipCode": "75001", "country": "FR"}
  }'
```

### RabbitMQ Sample

1. Start infrastructure:
```bash
docker-compose up -d
```

2. Run the application with the `rabbitmq` profile (sample module is in the
   `full` profile; it listens on port 8011 in rabbitmq mode):
```bash
mvn -Pfull -pl stepprflow-samples spring-boot:run -Dspring-boot.run.profiles=rabbitmq
```

## Running the Dashboard

### Option 1: Standalone Dashboard

```bash
docker-compose up -d  # Start Kafka & MongoDB
# The monitor is fail-closed until an auth mode is set, so pass one.
mvn -pl stepprflow-monitoring spring-boot:run \
  -Dspring-boot.run.arguments="--stepprflow.monitor.auth.mode=basic --stepprflow.monitor.auth.basic.username=admin --stepprflow.monitor.auth.basic.password=change-me --stepprflow.monitor.auth.basic.role=OPERATOR"
```

Access at: http://localhost:8090

### Option 2: With Docker

```bash
docker-compose up -d
```

## Frontend Development

### Setup

```bash
cd stepprflow-ui
npm install
```

### Development server

```bash
npm run dev
```

Access at: http://localhost:5173

### Build for production

```bash
npm run build
```

### Run tests

```bash
npm run test
npm run test:e2e  # Cypress E2E tests
```

## Code Quality

### Checkstyle

Run checkstyle validation:
```bash
mvn checkstyle:check
```

### SpotBugs

Run static analysis:
```bash
mvn spotbugs:check
```

### All quality checks

```bash
mvn verify -DskipTests
```

## Testing

### Unit Tests

```bash
mvn test
```

### Integration Tests

Integration tests require Docker:
```bash
mvn verify
```

### Coverage Report

```bash
mvn jacoco:report
```

Reports are generated in `target/site/jacoco/index.html`.

### Mutation Testing (Pitest)

Pitest performs mutation testing to measure test quality. It works by introducing small changes (mutations) to the code and checking if tests detect them.

**Run mutation testing on all modules:**
```bash
mvn verify -Ppitest
```

**Run on a specific module:**
```bash
mvn verify -Ppitest -pl stepprflow-core
```

**Run directly (without verify phase):**
```bash
mvn pitest:mutationCoverage -pl stepprflow-core
```

**Configuration:**
- Mutation threshold: 60% (mutations that must be killed)
- Coverage threshold: 80% (line coverage required)
- Uses 4 threads for parallel execution
- Excludes DTOs, models, configurations from mutation

**Output:**
- HTML report: `target/pit-reports/YYYYMMDDHHMI/index.html`
- XML report: `target/pit-reports/YYYYMMDDHHMI/mutations.xml`

**Incremental mode:**
Pitest uses history files to speed up subsequent runs by only testing mutations affected by code changes.

### Load Tests

```bash
cd stepprflow-load-tests
mvn gatling:test
```

## IDE Setup

### IntelliJ IDEA

1. Import as Maven project
2. Enable annotation processing (for Lombok)
3. Install plugins:
   - Lombok
   - CheckStyle-IDEA
   - Spring Boot

### VS Code

Recommended extensions:
- Extension Pack for Java
- Spring Boot Extension Pack
- Vue - Official
- Tailwind CSS IntelliSense

## Common Tasks

### Adding a new module

1. Create module directory
2. Add `pom.xml` with parent reference:
```xml
<parent>
    <groupId>io.github.stepprflow</groupId>
    <artifactId>stepprflow-parent</artifactId>
    <version>1.1.1</version>
</parent>
```

3. Add module to parent `pom.xml`:
```xml
<modules>
    ...
    <module>new-module</module>
</modules>
```

### Updating dependencies

Update a single dependency:
```bash
mvn versions:use-latest-versions -Dincludes=groupId:artifactId
```

Update all dependencies:
```bash
mvn versions:use-latest-versions
```

Check for updates:
```bash
mvn versions:display-dependency-updates
```

### Creating a release

Releases are automated via GitHub Actions:

1. Go to **Actions** > **Release**
2. Click **Run workflow**
3. Select version type (patch/minor/major)
4. Optionally mark as prerelease

Or manually:
```bash
mvn versions:set -DnewVersion=1.0.0
mvn clean deploy
git tag v1.0.0
git push --tags
```

## Troubleshooting

### Maven build fails

```bash
mvn clean install -U  # Force update snapshots
```

### Tests fail with Docker

Ensure Docker is running:
```bash
docker info
```

### Checkstyle errors

Run with details:
```bash
mvn checkstyle:check -Dcheckstyle.consoleOutput=true
```

### Port conflicts

Default ports:
- Dashboard: 8090
- MongoDB: 27017
- Kafka: 9092
- RabbitMQ: 5672, 15672

## Contributing

1. Create a feature branch from `develop`
2. Make your changes
3. Run all checks: `mvn verify`
4. Create a pull request

See the contributing section above for guidelines.

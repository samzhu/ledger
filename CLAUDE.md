# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Ledger is an LLM usage analytics service that consumes CloudEvents from an upstream LLM Gateway (Gate) and tracks API usage, costs, and quotas. It's a Spring Boot 3.5 application using Java 25 with GraalVM Native Image support.

## Build & Development Commands

```bash
# Build the project
./gradlew build

# Run tests (uses Testcontainers for MongoDB)
./gradlew test

# Run a single test class
./gradlew test --tests "io.github.samzhu.ledger.service.CostCalculationServiceTest"

# Compile only (useful for quick syntax checks)
./gradlew compileJava

# Start local development (auto-starts MongoDB + RabbitMQ via Docker Compose)
./gradlew bootRun

# Build native image
./gradlew nativeCompile

# Build container image
./gradlew bootBuildImage
```

## Architecture

### Event Processing Flow

```
Gate (Publisher) → Pub/Sub/RabbitMQ → UsageEventFunction → EventBufferService → MongoDB
                                                                    ↓
                                                         BatchSettlementService
                                                                    ↓
                                        raw_event_batches → daily_user_usage / daily_model_usage / system_stats
```

### Key Components

- **UsageEventFunction** (`function/`): Spring Cloud Function consumer that receives CloudEvents in structured mode (`application/cloudevents+json`)
- **EventBufferService** (`service/`): Buffers events in memory, batch-writes to `raw_event_batches` collection (size-based or cron-triggered)
- **BatchSettlementService** (`service/`): Hourly cron job processes unprocessed batches, aggregates into daily statistics
- **UsageAggregationService** (`service/`): Computes cost calculations using model pricing from configuration
- **CostCalculationService** (`service/`): Token-based cost calculation with cache pricing support

### MongoDB Collections

- `raw_event_batches`: Raw events awaiting processing (`processed: false/true`)
- `daily_user_usage`: Per-user daily aggregates
- `daily_model_usage`: Per-model daily aggregates
- `system_stats`: System-wide daily statistics
- `user_quota`: User quota configuration and tracking

### Configuration Profiles

Dual-layer profile architecture:
- **Infrastructure layer**: `local` (Docker Compose + RabbitMQ) or `gcp` (Pub/Sub + Secret Manager)
- **Behavior layer**: `dev`, `lab`, or `prod`

Default: `local,dev`

## Key Technical Considerations

### Java 25 + SpEL Compatibility

When returning `List` to Thymeleaf templates, wrap with `ArrayList` to avoid SpEL issues with `ImmutableCollections$ListN`:

```java
// BAD - SpEL cannot find methods like isEmpty(), size()
List<UserQuota> users = stream.toList();

// GOOD
List<UserQuota> users = new ArrayList<>(stream.toList());
```

### SVG in Thymeleaf Loops

Define SVG gradients/filters outside `th:each` loops to prevent duplicate IDs (see `docs/DEVELOPMENT-NOTES.md`).

### Native Image

The `jackson-datatype-joda` module is excluded from Spring Cloud Stream to avoid missing timezone resource errors in Native Image builds.

### Model Pricing

Add new model pricing in `application.yaml` under `ledger.pricing`:

```yaml
ledger:
  pricing:
    claude-opus-4-5-20251101:
      input-per-million: 5.00
      output-per-million: 25.00
      cache-read-per-million: 0.50
      cache-write-per-million: 6.25
```

## API Endpoints

### REST API (`/api/v1/usage`)
- `GET /users/{userId}/daily?startDate=&endDate=` - User daily usage
- `GET /models/{model}/daily?startDate=&endDate=` - Model daily usage
- `GET /system/daily?startDate=&endDate=` - System-wide usage
- `POST /flush/trigger` - Manual buffer flush
- `POST /settlement/trigger` - Manual batch settlement
- `POST /process/trigger` - Flush + settlement

### Dashboard (`/dashboard`)
- Overview, Users, Models, Quota management pages (Thymeleaf SSR)

## Testing

Tests use Testcontainers with MongoDB. The `TestcontainersConfiguration` class provides the `@ServiceConnection` for MongoDB:

```java
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class YourTest { }
```

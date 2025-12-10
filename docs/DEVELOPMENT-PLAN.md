# Ledger 開發計劃

## 文件資訊
- **建立日期**: 2025-12-10
- **專案名稱**: Ledger (LLM Usage Ledger)
- **技術棧**: Spring Boot 3.5.8 + Spring Data MongoDB + Spring Cloud Stream + Java 25

---

## 開發階段總覽

| 階段 | 名稱 | 任務數 | 說明 |
|------|------|--------|------|
| 1 | 專案初始化與基礎設施 | 6 | 建立專案骨架、依賴、配置 |
| 2 | 資料模型與 Repository | 5 | MongoDB Document 與資料存取層 |
| 3 | 事件消費與聚合引擎 | 6 | CloudEvents 消費、批量聚合 |
| 4 | REST API | 4 | 用量查詢 API |
| 5 | 儀表板 UI | 6 | Thymeleaf + Tailwind + Chart.js |
| 6 | 可觀測性與部署 | 5 | Tracing、Native Image、Cloud Run |

---

## 階段 1：專案初始化與基礎設施

### 任務 1.1：建立 Gradle 專案配置

**目標**: 配置 build.gradle 與專案基本結構

**檔案**:
- `build.gradle`
- `settings.gradle`

**內容**:
```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.5.8'
    id 'io.spring.dependency-management' version '1.1.7'
    id 'org.graalvm.buildtools.native' version '0.10.6'
}

group = 'io.github.samzhu'
version = '0.0.1-SNAPSHOT'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

ext {
    set('springCloudVersion', "2025.0.0")
    set('cloudeventsVersion', "4.0.1")
}

dependencies {
    // Web
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-thymeleaf'
    implementation 'org.springframework.boot:spring-boot-starter-validation'

    // MongoDB
    implementation 'org.springframework.boot:spring-boot-starter-data-mongodb'

    // Spring Cloud Stream
    implementation 'org.springframework.cloud:spring-cloud-stream'
    implementation 'org.springframework.cloud:spring-cloud-stream-binder-rabbit'
    implementation "com.google.cloud:spring-cloud-gcp-pubsub-stream-binder"

    // CloudEvents
    implementation "io.cloudevents:cloudevents-spring:4.0.1"

    // Observability
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'io.micrometer:micrometer-tracing-bridge-brave'
    runtimeOnly 'io.micrometer:micrometer-registry-otlp'

    // Dev
    developmentOnly 'org.springframework.boot:spring-boot-devtools'
    developmentOnly 'org.springframework.boot:spring-boot-docker-compose'
    annotationProcessor 'org.springframework.boot:spring-boot-configuration-processor'

    // Test
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.boot:spring-boot-testcontainers'
    testImplementation 'org.springframework.cloud:spring-cloud-stream-test-binder'
    testImplementation 'org.testcontainers:mongodb'
    testImplementation 'org.testcontainers:rabbitmq'
    testImplementation 'org.testcontainers:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

dependencyManagement {
    imports {
        mavenBom "org.springframework.cloud:spring-cloud-dependencies:${springCloudVersion}"
    }
}
```

**驗收條件**:
- [ ] `./gradlew build` 成功
- [ ] 依賴正確下載

---

### 任務 1.2：建立 Spring Boot 主程式

**目標**: 建立應用程式入口點

**檔案**:
- `src/main/java/io/github/samzhu/ledger/LedgerApplication.java`

**內容**:
```java
package io.github.samzhu.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LedgerApplication {
    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
```

**驗收條件**:
- [ ] 應用程式可啟動
- [ ] Actuator `/actuator/health` 回傳 UP

---

### 任務 1.3：建立 Docker Compose 本地環境

**目標**: 配置 MongoDB + RabbitMQ 本地開發環境

**檔案**:
- `compose.yaml`

**內容**:
```yaml
services:
  mongodb:
    image: mongo:7
    ports:
      - "27017:27017"
    environment:
      MONGO_INITDB_DATABASE: ledger
    volumes:
      - mongodb_data:/data/db

  rabbitmq:
    image: rabbitmq:4-management
    ports:
      - "5672:5672"
      - "15672:15672"
    environment:
      RABBITMQ_DEFAULT_USER: guest
      RABBITMQ_DEFAULT_PASS: guest

  # 可觀測性 (選用)
  grafana-lgtm:
    image: grafana/otel-lgtm:latest
    ports:
      - "3000:3000"   # Grafana UI
      - "4317:4317"   # OTLP gRPC
      - "4318:4318"   # OTLP HTTP

volumes:
  mongodb_data:
```

**驗收條件**:
- [ ] `docker compose up -d` 成功
- [ ] MongoDB 可連線 (localhost:27017)
- [ ] RabbitMQ 管理介面可存取 (localhost:15672)

---

### 任務 1.4：建立基礎配置檔

**目標**: 建立 application.yaml 與環境配置

**檔案**:
- `src/main/resources/application.yaml`
- `src/main/resources/application-local.yaml`

**application.yaml 內容**:
```yaml
spring:
  application:
    name: ledger
  profiles:
    default: local
  threads:
    virtual:
      enabled: true
  cloud:
    function:
      definition: usageEventConsumer
    stream:
      bindings:
        usageEventConsumer-in-0:
          destination: llm-gateway-usage
          group: ledger
          content-type: application/cloudevents+json

# 應用程式配置
ledger:
  batch:
    size: 100
    interval-ms: 5000
  pricing:
    # Claude Sonnet 4
    claude-sonnet-4-20250514:
      input-per-million: 3.00
      output-per-million: 15.00
      cache-read-per-million: 0.30
      cache-write-per-million: 3.75
    # Claude Opus 4
    claude-opus-4-20250514:
      input-per-million: 15.00
      output-per-million: 75.00
      cache-read-per-million: 1.50
      cache-write-per-million: 18.75
    # Claude Haiku 3.5
    claude-haiku-3-5-20241022:
      input-per-million: 0.80
      output-per-million: 4.00
      cache-read-per-million: 0.08
      cache-write-per-million: 1.00

# 優雅關閉
server:
  shutdown: graceful
  port: 8080

spring.lifecycle:
  timeout-per-shutdown-phase: 30s

# Actuator
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true

logging:
  level:
    root: INFO
    io.github.samzhu.ledger: DEBUG
```

**application-local.yaml 內容**:
```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/ledger
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
  cloud:
    stream:
      default-binder: rabbit
  thymeleaf:
    cache: false

logging:
  level:
    io.github.samzhu.ledger: DEBUG
```

**驗收條件**:
- [ ] 應用程式以 local profile 啟動成功
- [ ] 連線到 MongoDB 成功
- [ ] 連線到 RabbitMQ 成功

---

### 任務 1.5：建立配置屬性類別

**目標**: 建立類型安全的配置屬性

**檔案**:
- `src/main/java/io/github/samzhu/ledger/config/LedgerProperties.java`

**內容**:
```java
package io.github.samzhu.ledger.config;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ledger")
public record LedgerProperties(
    BatchConfig batch,
    Map<String, ModelPricing> pricing
) {
    public record BatchConfig(
        int size,
        long intervalMs
    ) {}

    public record ModelPricing(
        BigDecimal inputPerMillion,
        BigDecimal outputPerMillion,
        BigDecimal cacheReadPerMillion,
        BigDecimal cacheWritePerMillion
    ) {}
}
```

**檔案**:
- `src/main/java/io/github/samzhu/ledger/config/AppConfig.java`

**內容**:
```java
package io.github.samzhu.ledger.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LedgerProperties.class)
public class AppConfig {
}
```

**驗收條件**:
- [ ] LedgerProperties 可正確注入
- [ ] pricing Map 可讀取各模型定價

---

### 任務 1.6：建立 MongoDB 配置

**目標**: 啟用 MongoDB Auditing 和 Repository

**檔案**:
- `src/main/java/io/github/samzhu/ledger/config/MongoConfig.java`

**內容**:
```java
package io.github.samzhu.ledger.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@Configuration
@EnableMongoRepositories(basePackages = "io.github.samzhu.ledger.repository")
@EnableMongoAuditing
public class MongoConfig {
}
```

**驗收條件**:
- [ ] MongoDB Repository 可正常運作
- [ ] @CreatedDate, @LastModifiedDate 自動填充

---

## 階段 2：資料模型與 Repository

### 任務 2.1：建立 DailyUserUsage Document

**目標**: 建立每日用戶聚合資料模型

**檔案**:
- `src/main/java/io/github/samzhu/ledger/document/DailyUserUsage.java`

**內容**:
```java
package io.github.samzhu.ledger.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 每日用戶聚合
 * _id 格式: {date}_{userId}, 例如 "2025-12-09_user-uuid-123"
 */
@Document(collection = "daily_user_usage")
public record DailyUserUsage(
    @Id String id,
    LocalDate date,
    String userId,
    long totalInputTokens,
    long totalOutputTokens,
    long totalCacheCreationTokens,
    long totalCacheReadTokens,
    long totalTokens,
    int requestCount,
    int successCount,
    int errorCount,
    long totalLatencyMs,
    Map<String, ModelBreakdown> modelBreakdown,
    BigDecimal estimatedCostUsd,
    @LastModifiedDate Instant lastUpdatedAt
) {
    public record ModelBreakdown(
        long inputTokens,
        long outputTokens,
        int requestCount
    ) {}

    public static String createId(LocalDate date, String userId) {
        return date.toString() + "_" + userId;
    }
}
```

**驗收條件**:
- [ ] Document 可正常序列化/反序列化
- [ ] createId() 產生正確格式

---

### 任務 2.2：建立 DailyModelUsage Document

**目標**: 建立每日模型聚合資料模型

**檔案**:
- `src/main/java/io/github/samzhu/ledger/document/DailyModelUsage.java`

**內容**:
```java
package io.github.samzhu.ledger.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 每日模型聚合
 * _id 格式: {date}_{model}, 例如 "2025-12-09_claude-sonnet-4-20250514"
 */
@Document(collection = "daily_model_usage")
public record DailyModelUsage(
    @Id String id,
    LocalDate date,
    String model,
    long totalInputTokens,
    long totalOutputTokens,
    long totalCacheCreationTokens,
    long totalCacheReadTokens,
    long totalTokens,
    int requestCount,
    int successCount,
    int errorCount,
    int uniqueUsers,
    long avgLatencyMs,
    BigDecimal estimatedCostUsd,
    @LastModifiedDate Instant lastUpdatedAt
) {
    public static String createId(LocalDate date, String model) {
        return date.toString() + "_" + model;
    }
}
```

**驗收條件**:
- [ ] Document 可正常序列化/反序列化

---

### 任務 2.3：建立 UserSummary Document

**目標**: 建立用戶總計資料模型

**檔案**:
- `src/main/java/io/github/samzhu/ledger/document/UserSummary.java`

**內容**:
```java
package io.github.samzhu.ledger.document;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 用戶總計
 * _id: userId
 */
@Document(collection = "user_summary")
public record UserSummary(
    @Id String id,
    String userId,
    long totalInputTokens,
    long totalOutputTokens,
    long totalTokens,
    int totalRequestCount,
    BigDecimal totalEstimatedCostUsd,
    Instant firstSeenAt,
    Instant lastActiveAt,
    @LastModifiedDate Instant lastUpdatedAt
) {
}
```

**驗收條件**:
- [ ] Document 可正常序列化/反序列化

---

### 任務 2.4：建立 SystemStats Document

**目標**: 建立系統層級統計資料模型

**檔案**:
- `src/main/java/io/github/samzhu/ledger/document/SystemStats.java`

**內容**:
```java
package io.github.samzhu.ledger.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 系統每日統計
 * _id: date (YYYY-MM-DD)
 */
@Document(collection = "system_stats")
public record SystemStats(
    @Id String id,
    LocalDate date,
    long totalInputTokens,
    long totalOutputTokens,
    long totalTokens,
    int totalRequestCount,
    int uniqueUsers,
    BigDecimal totalEstimatedCostUsd,
    List<TopModel> topModels,
    List<TopUser> topUsers,
    @LastModifiedDate Instant lastUpdatedAt
) {
    public record TopModel(String model, int requestCount) {}
    public record TopUser(String userId, int requestCount) {}
}
```

**驗收條件**:
- [ ] Document 可正常序列化/反序列化

---

### 任務 2.5：建立 Repository 介面

**目標**: 建立 Spring Data MongoDB Repository

**檔案**:
- `src/main/java/io/github/samzhu/ledger/repository/DailyUserUsageRepository.java`
- `src/main/java/io/github/samzhu/ledger/repository/DailyModelUsageRepository.java`
- `src/main/java/io/github/samzhu/ledger/repository/UserSummaryRepository.java`
- `src/main/java/io/github/samzhu/ledger/repository/SystemStatsRepository.java`

**DailyUserUsageRepository.java**:
```java
package io.github.samzhu.ledger.repository;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import io.github.samzhu.ledger.document.DailyUserUsage;

public interface DailyUserUsageRepository extends MongoRepository<DailyUserUsage, String> {
    List<DailyUserUsage> findByIdIn(List<String> ids);
    List<DailyUserUsage> findByUserIdOrderByDateDesc(String userId);
}
```

**DailyModelUsageRepository.java**:
```java
package io.github.samzhu.ledger.repository;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import io.github.samzhu.ledger.document.DailyModelUsage;

public interface DailyModelUsageRepository extends MongoRepository<DailyModelUsage, String> {
    List<DailyModelUsage> findByIdIn(List<String> ids);
    List<DailyModelUsage> findByModelOrderByDateDesc(String model);
}
```

**UserSummaryRepository.java**:
```java
package io.github.samzhu.ledger.repository;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import io.github.samzhu.ledger.document.UserSummary;

public interface UserSummaryRepository extends MongoRepository<UserSummary, String> {
    List<UserSummary> findAllByOrderByTotalTokensDesc(Pageable pageable);
}
```

**SystemStatsRepository.java**:
```java
package io.github.samzhu.ledger.repository;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import io.github.samzhu.ledger.document.SystemStats;

public interface SystemStatsRepository extends MongoRepository<SystemStats, String> {
    List<SystemStats> findByIdIn(List<String> ids);
}
```

**驗收條件**:
- [ ] Repository 可正常注入
- [ ] CRUD 操作正常
- [ ] findByIdIn 查詢正常

---

## 階段 3：事件消費與聚合引擎

### 任務 3.1：建立 UsageEvent DTO

**目標**: 建立內部使用的用量事件 DTO

**檔案**:
- `src/main/java/io/github/samzhu/ledger/dto/UsageEvent.java`
- `src/main/java/io/github/samzhu/ledger/dto/UsageEventData.java`

**UsageEvent.java**:
```java
package io.github.samzhu.ledger.dto;

import java.time.LocalDate;

/**
 * 內部用量事件 DTO
 */
public record UsageEvent(
    String eventId,
    String userId,
    String model,
    int inputTokens,
    int outputTokens,
    int cacheCreationTokens,
    int cacheReadTokens,
    int totalTokens,
    long latencyMs,
    boolean stream,
    String stopReason,
    String status,
    String keyAlias,
    String traceId,
    String messageId,
    String anthropicRequestId,
    LocalDate date
) {
    public boolean isSuccess() {
        return "success".equalsIgnoreCase(status);
    }
}
```

**UsageEventData.java** (CloudEvents data payload):
```java
package io.github.samzhu.ledger.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * CloudEvents data payload (來自 Gate)
 * 使用 snake_case 對應 Gate 發送的 JSON
 */
public record UsageEventData(
    String model,
    @JsonProperty("message_id") String messageId,
    @JsonProperty("input_tokens") int inputTokens,
    @JsonProperty("output_tokens") int outputTokens,
    @JsonProperty("cache_creation_tokens") int cacheCreationTokens,
    @JsonProperty("cache_read_tokens") int cacheReadTokens,
    @JsonProperty("total_tokens") int totalTokens,
    @JsonProperty("latency_ms") long latencyMs,
    boolean stream,
    @JsonProperty("stop_reason") String stopReason,
    String status,
    @JsonProperty("key_alias") String keyAlias,
    @JsonProperty("trace_id") String traceId,
    @JsonProperty("anthropic_request_id") String anthropicRequestId
) {
}
```

**驗收條件**:
- [ ] JSON 反序列化正確 (snake_case → camelCase)
- [ ] UsageEvent.isSuccess() 正常運作

---

### 任務 3.2：建立 CostCalculationService

**目標**: 實作 Token 成本計算

**檔案**:
- `src/main/java/io/github/samzhu/ledger/service/CostCalculationService.java`

**內容**:
```java
package io.github.samzhu.ledger.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.samzhu.ledger.config.LedgerProperties;
import io.github.samzhu.ledger.config.LedgerProperties.ModelPricing;
import io.github.samzhu.ledger.dto.UsageEvent;

@Service
public class CostCalculationService {

    private static final Logger log = LoggerFactory.getLogger(CostCalculationService.class);
    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    private final LedgerProperties properties;

    public CostCalculationService(LedgerProperties properties) {
        this.properties = properties;
    }

    /**
     * 計算單一事件的成本
     */
    public BigDecimal calculateCost(UsageEvent event) {
        ModelPricing pricing = findPricing(event.model());
        if (pricing == null) {
            log.warn("Unknown model pricing: {}, using zero cost", event.model());
            return BigDecimal.ZERO;
        }

        // Input cost (扣除 cache read)
        int billableInputTokens = event.inputTokens() - event.cacheReadTokens();
        BigDecimal inputCost = calculateTokenCost(billableInputTokens, pricing.inputPerMillion());

        // Cache read cost
        BigDecimal cacheReadCost = calculateTokenCost(event.cacheReadTokens(), pricing.cacheReadPerMillion());

        // Cache write cost
        BigDecimal cacheWriteCost = calculateTokenCost(event.cacheCreationTokens(), pricing.cacheWritePerMillion());

        // Output cost
        BigDecimal outputCost = calculateTokenCost(event.outputTokens(), pricing.outputPerMillion());

        return inputCost.add(cacheReadCost).add(cacheWriteCost).add(outputCost);
    }

    private BigDecimal calculateTokenCost(int tokens, BigDecimal pricePerMillion) {
        if (tokens <= 0 || pricePerMillion == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(tokens)
            .multiply(pricePerMillion)
            .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
    }

    private ModelPricing findPricing(String model) {
        // 直接匹配
        if (properties.pricing().containsKey(model)) {
            return properties.pricing().get(model);
        }

        // 模糊匹配 (處理版本號變化)
        for (var entry : properties.pricing().entrySet()) {
            if (model.startsWith(entry.getKey().substring(0, Math.min(15, entry.getKey().length())))) {
                return entry.getValue();
            }
        }

        return null;
    }
}
```

**驗收條件**:
- [ ] 正確計算 Sonnet/Opus/Haiku 成本
- [ ] 處理未知模型 (回傳 0)
- [ ] Cache token 計算正確

---

### 任務 3.3：建立 UsageAggregationService

**目標**: 實作批量聚合更新

**檔案**:
- `src/main/java/io/github/samzhu/ledger/service/UsageAggregationService.java`

**內容**:
```java
package io.github.samzhu.ledger.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import io.github.samzhu.ledger.document.DailyModelUsage;
import io.github.samzhu.ledger.document.DailyUserUsage;
import io.github.samzhu.ledger.document.SystemStats;
import io.github.samzhu.ledger.document.UserSummary;
import io.github.samzhu.ledger.dto.UsageEvent;

@Service
public class UsageAggregationService {

    private static final Logger log = LoggerFactory.getLogger(UsageAggregationService.class);

    private final MongoTemplate mongoTemplate;
    private final CostCalculationService costService;

    public UsageAggregationService(MongoTemplate mongoTemplate, CostCalculationService costService) {
        this.mongoTemplate = mongoTemplate;
        this.costService = costService;
    }

    /**
     * 批量處理事件 - 更新所有聚合
     */
    public void processBatch(List<UsageEvent> events) {
        if (events.isEmpty()) {
            return;
        }

        long startTime = System.currentTimeMillis();

        updateDailyUserUsage(events);
        updateDailyModelUsage(events);
        updateUserSummary(events);
        updateSystemStats(events);

        log.info("Processed {} events in {}ms", events.size(), System.currentTimeMillis() - startTime);
    }

    private void updateDailyUserUsage(List<UsageEvent> events) {
        BulkOperations bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, DailyUserUsage.class);

        Map<String, List<UsageEvent>> grouped = events.stream()
            .collect(Collectors.groupingBy(e -> DailyUserUsage.createId(e.date(), e.userId())));

        grouped.forEach((docId, userEvents) -> {
            UsageEvent first = userEvents.get(0);

            long totalInput = userEvents.stream().mapToLong(UsageEvent::inputTokens).sum();
            long totalOutput = userEvents.stream().mapToLong(UsageEvent::outputTokens).sum();
            long totalCacheCreation = userEvents.stream().mapToLong(UsageEvent::cacheCreationTokens).sum();
            long totalCacheRead = userEvents.stream().mapToLong(UsageEvent::cacheReadTokens).sum();
            long totalTokens = userEvents.stream().mapToLong(UsageEvent::totalTokens).sum();
            long totalLatency = userEvents.stream().mapToLong(UsageEvent::latencyMs).sum();
            int successCount = (int) userEvents.stream().filter(UsageEvent::isSuccess).count();
            int errorCount = userEvents.size() - successCount;

            BigDecimal totalCost = userEvents.stream()
                .map(costService::calculateCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            Query query = Query.query(Criteria.where("_id").is(docId));
            Update update = new Update()
                .setOnInsert("date", first.date())
                .setOnInsert("userId", first.userId())
                .inc("totalInputTokens", totalInput)
                .inc("totalOutputTokens", totalOutput)
                .inc("totalCacheCreationTokens", totalCacheCreation)
                .inc("totalCacheReadTokens", totalCacheRead)
                .inc("totalTokens", totalTokens)
                .inc("totalLatencyMs", totalLatency)
                .inc("requestCount", userEvents.size())
                .inc("successCount", successCount)
                .inc("errorCount", errorCount)
                .inc("estimatedCostUsd", totalCost.doubleValue())
                .set("lastUpdatedAt", Instant.now());

            bulkOps.upsert(query, update);
        });

        bulkOps.execute();
    }

    private void updateDailyModelUsage(List<UsageEvent> events) {
        BulkOperations bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, DailyModelUsage.class);

        Map<String, List<UsageEvent>> grouped = events.stream()
            .collect(Collectors.groupingBy(e -> DailyModelUsage.createId(e.date(), e.model())));

        grouped.forEach((docId, modelEvents) -> {
            UsageEvent first = modelEvents.get(0);

            long totalInput = modelEvents.stream().mapToLong(UsageEvent::inputTokens).sum();
            long totalOutput = modelEvents.stream().mapToLong(UsageEvent::outputTokens).sum();
            long totalCacheCreation = modelEvents.stream().mapToLong(UsageEvent::cacheCreationTokens).sum();
            long totalCacheRead = modelEvents.stream().mapToLong(UsageEvent::cacheReadTokens).sum();
            long totalTokens = modelEvents.stream().mapToLong(UsageEvent::totalTokens).sum();
            int successCount = (int) modelEvents.stream().filter(UsageEvent::isSuccess).count();
            int errorCount = modelEvents.size() - successCount;

            BigDecimal totalCost = modelEvents.stream()
                .map(costService::calculateCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            Query query = Query.query(Criteria.where("_id").is(docId));
            Update update = new Update()
                .setOnInsert("date", first.date())
                .setOnInsert("model", first.model())
                .inc("totalInputTokens", totalInput)
                .inc("totalOutputTokens", totalOutput)
                .inc("totalCacheCreationTokens", totalCacheCreation)
                .inc("totalCacheReadTokens", totalCacheRead)
                .inc("totalTokens", totalTokens)
                .inc("requestCount", modelEvents.size())
                .inc("successCount", successCount)
                .inc("errorCount", errorCount)
                .inc("estimatedCostUsd", totalCost.doubleValue())
                .set("lastUpdatedAt", Instant.now());

            bulkOps.upsert(query, update);
        });

        bulkOps.execute();
    }

    private void updateUserSummary(List<UsageEvent> events) {
        BulkOperations bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, UserSummary.class);

        Map<String, List<UsageEvent>> grouped = events.stream()
            .collect(Collectors.groupingBy(UsageEvent::userId));

        grouped.forEach((userId, userEvents) -> {
            long totalInput = userEvents.stream().mapToLong(UsageEvent::inputTokens).sum();
            long totalOutput = userEvents.stream().mapToLong(UsageEvent::outputTokens).sum();
            long totalTokens = userEvents.stream().mapToLong(UsageEvent::totalTokens).sum();

            BigDecimal totalCost = userEvents.stream()
                .map(costService::calculateCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            Query query = Query.query(Criteria.where("_id").is(userId));
            Update update = new Update()
                .setOnInsert("userId", userId)
                .setOnInsert("firstSeenAt", Instant.now())
                .inc("totalInputTokens", totalInput)
                .inc("totalOutputTokens", totalOutput)
                .inc("totalTokens", totalTokens)
                .inc("totalRequestCount", userEvents.size())
                .inc("totalEstimatedCostUsd", totalCost.doubleValue())
                .set("lastActiveAt", Instant.now())
                .set("lastUpdatedAt", Instant.now());

            bulkOps.upsert(query, update);
        });

        bulkOps.execute();
    }

    private void updateSystemStats(List<UsageEvent> events) {
        BulkOperations bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, SystemStats.class);

        Map<LocalDate, List<UsageEvent>> grouped = events.stream()
            .collect(Collectors.groupingBy(UsageEvent::date));

        grouped.forEach((date, dateEvents) -> {
            long totalInput = dateEvents.stream().mapToLong(UsageEvent::inputTokens).sum();
            long totalOutput = dateEvents.stream().mapToLong(UsageEvent::outputTokens).sum();
            long totalTokens = dateEvents.stream().mapToLong(UsageEvent::totalTokens).sum();

            BigDecimal totalCost = dateEvents.stream()
                .map(costService::calculateCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            Query query = Query.query(Criteria.where("_id").is(date.toString()));
            Update update = new Update()
                .setOnInsert("date", date)
                .inc("totalInputTokens", totalInput)
                .inc("totalOutputTokens", totalOutput)
                .inc("totalTokens", totalTokens)
                .inc("totalRequestCount", dateEvents.size())
                .inc("totalEstimatedCostUsd", totalCost.doubleValue())
                .set("lastUpdatedAt", Instant.now());

            bulkOps.upsert(query, update);
        });

        bulkOps.execute();
    }
}
```

**驗收條件**:
- [ ] bulkOps 批量寫入正常
- [ ] $inc 原子更新正確累加
- [ ] upsert 正確處理新/舊文件

---

### 任務 3.4：建立 EventBufferService

**目標**: 實作事件緩衝與優雅關閉

**檔案**:
- `src/main/java/io/github/samzhu/ledger/service/EventBufferService.java`

**內容**:
```java
package io.github.samzhu.ledger.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import io.github.samzhu.ledger.config.LedgerProperties;
import io.github.samzhu.ledger.dto.UsageEvent;

/**
 * 事件緩衝服務
 *
 * 特性:
 * 1. 累積事件到達批量大小時觸發寫入
 * 2. 定時檢查並寫入累積事件
 * 3. 優雅關閉時確保所有事件寫入資料庫
 */
@Service
public class EventBufferService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EventBufferService.class);

    private final UsageAggregationService aggregationService;
    private final List<UsageEvent> eventBuffer = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final int batchSize;
    private final long flushIntervalMs;

    public EventBufferService(UsageAggregationService aggregationService, LedgerProperties properties) {
        this.aggregationService = aggregationService;
        this.batchSize = properties.batch().size();
        this.flushIntervalMs = properties.batch().intervalMs();
    }

    /**
     * 加入事件到緩衝區
     */
    public void addEvent(UsageEvent event) {
        eventBuffer.add(event);

        if (eventBuffer.size() >= batchSize) {
            flushBuffer();
        }
    }

    /**
     * 將緩衝區事件寫入資料庫
     */
    public synchronized void flushBuffer() {
        if (eventBuffer.isEmpty()) {
            return;
        }

        List<UsageEvent> batch = new ArrayList<>(eventBuffer);
        eventBuffer.clear();

        try {
            aggregationService.processBatch(batch);
            log.debug("Flushed {} events to database", batch.size());
        } catch (Exception e) {
            log.error("Failed to flush events, re-adding to buffer: {}", e.getMessage(), e);
            eventBuffer.addAll(0, batch);
        }
    }

    // ===== SmartLifecycle 實作 =====

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(
                this::flushBuffer,
                flushIntervalMs,
                flushIntervalMs,
                TimeUnit.MILLISECONDS
            );
            log.info("EventBufferService started: batchSize={}, flushIntervalMs={}", batchSize, flushIntervalMs);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("EventBufferService stopping, flushing remaining {} events...", eventBuffer.size());

            scheduler.shutdown();
            try {
                scheduler.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 確保所有累積的事件都寫入資料庫
            flushBuffer();

            log.info("EventBufferService stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        // 在 Spring Cloud Stream bindings 之後關閉
        return Integer.MAX_VALUE - 100;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    /**
     * 取得目前緩衝區大小 (用於監控)
     */
    public int getBufferSize() {
        return eventBuffer.size();
    }
}
```

**驗收條件**:
- [ ] 累積達 batchSize 時自動 flush
- [ ] 定時 flush 正常運作
- [ ] SIGTERM 時正確 flush 殘餘事件
- [ ] 失敗時事件放回 buffer

---

### 任務 3.5：建立 CloudEvents 消費者

**目標**: 實作 Spring Cloud Stream Function 消費者

**檔案**:
- `src/main/java/io/github/samzhu/ledger/function/UsageEventFunction.java`

**內容**:
```java
package io.github.samzhu.ledger.function;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.CloudEventUtils;
import io.cloudevents.jackson.PojoCloudEventDataMapper;
import io.github.samzhu.ledger.dto.UsageEvent;
import io.github.samzhu.ledger.dto.UsageEventData;
import io.github.samzhu.ledger.service.EventBufferService;

/**
 * Spring Cloud Stream 消費者 - Function 風格
 * Binding name: usageEventConsumer-in-0
 */
@Configuration
public class UsageEventFunction {

    private static final Logger log = LoggerFactory.getLogger(UsageEventFunction.class);

    private final EventBufferService bufferService;
    private final ObjectMapper objectMapper;

    public UsageEventFunction(EventBufferService bufferService, ObjectMapper objectMapper) {
        this.bufferService = bufferService;
        this.objectMapper = objectMapper;
    }

    @Bean
    public Consumer<Message<CloudEvent>> usageEventConsumer() {
        return message -> {
            try {
                CloudEvent cloudEvent = message.getPayload();
                UsageEvent event = parseUsageEvent(cloudEvent);

                bufferService.addEvent(event);

                log.debug("Event received: traceId={}, userId={}, model={}",
                    event.traceId(), event.userId(), event.model());
            } catch (Exception e) {
                log.error("Failed to process usage event: {}", e.getMessage(), e);
                // 不拋出異常，避免訊息重複消費
            }
        };
    }

    private UsageEvent parseUsageEvent(CloudEvent cloudEvent) {
        String userId = cloudEvent.getSubject();
        OffsetDateTime time = cloudEvent.getTime();

        UsageEventData data = CloudEventUtils.mapData(
            cloudEvent,
            PojoCloudEventDataMapper.from(objectMapper, UsageEventData.class)
        ).getValue();

        return new UsageEvent(
            cloudEvent.getId(),
            userId,
            data.model(),
            data.inputTokens(),
            data.outputTokens(),
            data.cacheCreationTokens(),
            data.cacheReadTokens(),
            data.totalTokens(),
            data.latencyMs(),
            data.stream(),
            data.stopReason(),
            data.status(),
            data.keyAlias(),
            data.traceId(),
            data.messageId(),
            data.anthropicRequestId(),
            time != null ? time.toLocalDate() : LocalDate.now()
        );
    }
}
```

**驗收條件**:
- [ ] 正確解析 CloudEvents
- [ ] 事件加入 buffer
- [ ] 錯誤不中斷消費

---

### 任務 3.6：整合測試 - 事件消費流程

**目標**: 測試完整的事件消費到聚合流程

**檔案**:
- `src/test/java/io/github/samzhu/ledger/integration/UsageEventIntegrationTest.java`

**內容**:
```java
package io.github.samzhu.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.github.samzhu.ledger.document.DailyUserUsage;
import io.github.samzhu.ledger.dto.UsageEvent;
import io.github.samzhu.ledger.repository.DailyUserUsageRepository;
import io.github.samzhu.ledger.service.EventBufferService;

@SpringBootTest
@Testcontainers
class UsageEventIntegrationTest {

    @Container
    static MongoDBContainer mongodb = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongodb::getReplicaSetUrl);
    }

    @Autowired
    private EventBufferService bufferService;

    @Autowired
    private DailyUserUsageRepository dailyUserUsageRepository;

    @BeforeEach
    void setUp() {
        dailyUserUsageRepository.deleteAll();
    }

    @Test
    void shouldAggregateUsageEvents() {
        // Given
        LocalDate today = LocalDate.now();
        String userId = "test-user-123";

        UsageEvent event1 = new UsageEvent(
            "event-1", userId, "claude-sonnet-4-20250514",
            100, 50, 0, 0, 150, 1000L,
            true, "end_turn", "success", "primary",
            "trace-1", "msg-1", "req-1", today
        );

        UsageEvent event2 = new UsageEvent(
            "event-2", userId, "claude-sonnet-4-20250514",
            200, 100, 0, 0, 300, 2000L,
            true, "end_turn", "success", "primary",
            "trace-2", "msg-2", "req-2", today
        );

        // When
        bufferService.addEvent(event1);
        bufferService.addEvent(event2);
        bufferService.flushBuffer();

        // Then
        String docId = DailyUserUsage.createId(today, userId);
        DailyUserUsage usage = dailyUserUsageRepository.findById(docId).orElseThrow();

        assertThat(usage.totalInputTokens()).isEqualTo(300);
        assertThat(usage.totalOutputTokens()).isEqualTo(150);
        assertThat(usage.totalTokens()).isEqualTo(450);
        assertThat(usage.requestCount()).isEqualTo(2);
        assertThat(usage.successCount()).isEqualTo(2);
    }
}
```

**驗收條件**:
- [ ] 測試通過
- [ ] 聚合數據正確累加

---

## 階段 4：REST API

### 任務 4.1：建立 UsageQueryService

**目標**: 實作用量查詢服務

**檔案**:
- `src/main/java/io/github/samzhu/ledger/service/UsageQueryService.java`

**內容**:
```java
package io.github.samzhu.ledger.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import io.github.samzhu.ledger.document.DailyModelUsage;
import io.github.samzhu.ledger.document.DailyUserUsage;
import io.github.samzhu.ledger.document.SystemStats;
import io.github.samzhu.ledger.document.UserSummary;
import io.github.samzhu.ledger.repository.DailyModelUsageRepository;
import io.github.samzhu.ledger.repository.DailyUserUsageRepository;
import io.github.samzhu.ledger.repository.SystemStatsRepository;
import io.github.samzhu.ledger.repository.UserSummaryRepository;

@Service
public class UsageQueryService {

    private final DailyUserUsageRepository dailyUserUsageRepository;
    private final DailyModelUsageRepository dailyModelUsageRepository;
    private final UserSummaryRepository userSummaryRepository;
    private final SystemStatsRepository systemStatsRepository;

    public UsageQueryService(
            DailyUserUsageRepository dailyUserUsageRepository,
            DailyModelUsageRepository dailyModelUsageRepository,
            UserSummaryRepository userSummaryRepository,
            SystemStatsRepository systemStatsRepository) {
        this.dailyUserUsageRepository = dailyUserUsageRepository;
        this.dailyModelUsageRepository = dailyModelUsageRepository;
        this.userSummaryRepository = userSummaryRepository;
        this.systemStatsRepository = systemStatsRepository;
    }

    /**
     * 查詢用戶每日用量 (使用 findByIdIn 一次查詢)
     */
    public List<DailyUserUsage> getUserDailyUsage(String userId, LocalDate startDate, LocalDate endDate) {
        List<String> docIds = generateDateUserIds(startDate, endDate, userId);
        return dailyUserUsageRepository.findByIdIn(docIds);
    }

    /**
     * 查詢模型每日用量
     */
    public List<DailyModelUsage> getModelDailyUsage(String model, LocalDate startDate, LocalDate endDate) {
        List<String> docIds = generateDateModelIds(startDate, endDate, model);
        return dailyModelUsageRepository.findByIdIn(docIds);
    }

    /**
     * 查詢系統每日統計
     */
    public List<SystemStats> getSystemDailyStats(LocalDate startDate, LocalDate endDate) {
        List<String> docIds = generateDateIds(startDate, endDate);
        return systemStatsRepository.findByIdIn(docIds);
    }

    /**
     * 查詢用戶總計
     */
    public Optional<UserSummary> getUserSummary(String userId) {
        return userSummaryRepository.findById(userId);
    }

    /**
     * 查詢 Top N 用戶
     */
    public List<UserSummary> getTopUsers(int limit) {
        return userSummaryRepository.findAllByOrderByTotalTokensDesc(PageRequest.of(0, limit));
    }

    /**
     * 查詢所有用戶
     */
    public List<UserSummary> getAllUsers() {
        return userSummaryRepository.findAll();
    }

    private List<String> generateDateUserIds(LocalDate start, LocalDate end, String userId) {
        List<String> ids = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            ids.add(DailyUserUsage.createId(date, userId));
        }
        return ids;
    }

    private List<String> generateDateModelIds(LocalDate start, LocalDate end, String model) {
        List<String> ids = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            ids.add(DailyModelUsage.createId(date, model));
        }
        return ids;
    }

    private List<String> generateDateIds(LocalDate start, LocalDate end) {
        List<String> ids = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            ids.add(date.toString());
        }
        return ids;
    }
}
```

**驗收條件**:
- [ ] 使用 findByIdIn 最佳化查詢
- [ ] 日期範圍查詢正確

---

### 任務 4.2：建立 API Response DTO

**目標**: 建立 API 回應資料結構

**檔案**:
- `src/main/java/io/github/samzhu/ledger/dto/api/UserUsageResponse.java`
- `src/main/java/io/github/samzhu/ledger/dto/api/ModelUsageResponse.java`
- `src/main/java/io/github/samzhu/ledger/dto/api/SystemUsageResponse.java`
- `src/main/java/io/github/samzhu/ledger/dto/api/DailyUsage.java`
- `src/main/java/io/github/samzhu/ledger/dto/api/UsageSummary.java`
- `src/main/java/io/github/samzhu/ledger/dto/api/DatePeriod.java`

**UserUsageResponse.java**:
```java
package io.github.samzhu.ledger.dto.api;

import java.util.List;

public record UserUsageResponse(
    String userId,
    DatePeriod period,
    UsageSummary summary,
    List<DailyUsage> daily
) {}
```

**ModelUsageResponse.java**:
```java
package io.github.samzhu.ledger.dto.api;

import java.util.List;

public record ModelUsageResponse(
    String model,
    DatePeriod period,
    UsageSummary summary,
    List<DailyUsage> daily
) {}
```

**SystemUsageResponse.java**:
```java
package io.github.samzhu.ledger.dto.api;

import java.util.List;

public record SystemUsageResponse(
    DatePeriod period,
    UsageSummary summary,
    List<DailyUsage> daily,
    List<TopItem> topUsers,
    List<TopItem> topModels
) {
    public record TopItem(String id, String name, int requestCount, long totalTokens) {}
}
```

**DailyUsage.java**:
```java
package io.github.samzhu.ledger.dto.api;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyUsage(
    LocalDate date,
    long inputTokens,
    long outputTokens,
    long totalTokens,
    int requests,
    BigDecimal costUsd
) {}
```

**UsageSummary.java**:
```java
package io.github.samzhu.ledger.dto.api;

import java.math.BigDecimal;

public record UsageSummary(
    long totalInputTokens,
    long totalOutputTokens,
    long totalTokens,
    int totalRequests,
    BigDecimal estimatedCostUsd
) {}
```

**DatePeriod.java**:
```java
package io.github.samzhu.ledger.dto.api;

import java.time.LocalDate;

public record DatePeriod(
    LocalDate start,
    LocalDate end
) {}
```

**驗收條件**:
- [ ] 所有 DTO 建立完成

---

### 任務 4.3：建立 UsageApiController

**目標**: 實作 REST API 端點

**檔案**:
- `src/main/java/io/github/samzhu/ledger/controller/UsageApiController.java`

**內容**:
```java
package io.github.samzhu.ledger.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.samzhu.ledger.document.DailyModelUsage;
import io.github.samzhu.ledger.document.DailyUserUsage;
import io.github.samzhu.ledger.document.SystemStats;
import io.github.samzhu.ledger.document.UserSummary;
import io.github.samzhu.ledger.dto.api.*;
import io.github.samzhu.ledger.service.UsageQueryService;

@RestController
@RequestMapping("/api/v1/usage")
public class UsageApiController {

    private final UsageQueryService queryService;

    public UsageApiController(UsageQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * GET /api/v1/usage/users/{userId}/daily
     */
    @GetMapping("/users/{userId}/daily")
    public ResponseEntity<UserUsageResponse> getUserDailyUsage(
            @PathVariable String userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        List<DailyUserUsage> usages = queryService.getUserDailyUsage(userId, startDate, endDate);

        List<DailyUsage> daily = usages.stream()
            .map(u -> new DailyUsage(
                u.date(),
                u.totalInputTokens(),
                u.totalOutputTokens(),
                u.totalTokens(),
                u.requestCount(),
                u.estimatedCostUsd()
            ))
            .sorted((a, b) -> a.date().compareTo(b.date()))
            .toList();

        UsageSummary summary = new UsageSummary(
            daily.stream().mapToLong(DailyUsage::inputTokens).sum(),
            daily.stream().mapToLong(DailyUsage::outputTokens).sum(),
            daily.stream().mapToLong(DailyUsage::totalTokens).sum(),
            daily.stream().mapToInt(DailyUsage::requests).sum(),
            daily.stream().map(DailyUsage::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add)
        );

        return ResponseEntity.ok(new UserUsageResponse(
            userId,
            new DatePeriod(startDate, endDate),
            summary,
            daily
        ));
    }

    /**
     * GET /api/v1/usage/models/{model}/daily
     */
    @GetMapping("/models/{model}/daily")
    public ResponseEntity<ModelUsageResponse> getModelDailyUsage(
            @PathVariable String model,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        List<DailyModelUsage> usages = queryService.getModelDailyUsage(model, startDate, endDate);

        List<DailyUsage> daily = usages.stream()
            .map(u -> new DailyUsage(
                u.date(),
                u.totalInputTokens(),
                u.totalOutputTokens(),
                u.totalTokens(),
                u.requestCount(),
                u.estimatedCostUsd()
            ))
            .sorted((a, b) -> a.date().compareTo(b.date()))
            .toList();

        UsageSummary summary = new UsageSummary(
            daily.stream().mapToLong(DailyUsage::inputTokens).sum(),
            daily.stream().mapToLong(DailyUsage::outputTokens).sum(),
            daily.stream().mapToLong(DailyUsage::totalTokens).sum(),
            daily.stream().mapToInt(DailyUsage::requests).sum(),
            daily.stream().map(DailyUsage::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add)
        );

        return ResponseEntity.ok(new ModelUsageResponse(
            model,
            new DatePeriod(startDate, endDate),
            summary,
            daily
        ));
    }

    /**
     * GET /api/v1/usage/system/daily
     */
    @GetMapping("/system/daily")
    public ResponseEntity<SystemUsageResponse> getSystemDailyUsage(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        List<SystemStats> stats = queryService.getSystemDailyStats(startDate, endDate);
        List<UserSummary> topUsers = queryService.getTopUsers(10);

        List<DailyUsage> daily = stats.stream()
            .map(s -> new DailyUsage(
                s.date(),
                s.totalInputTokens(),
                s.totalOutputTokens(),
                s.totalTokens(),
                s.totalRequestCount(),
                s.totalEstimatedCostUsd()
            ))
            .sorted((a, b) -> a.date().compareTo(b.date()))
            .toList();

        UsageSummary summary = new UsageSummary(
            daily.stream().mapToLong(DailyUsage::inputTokens).sum(),
            daily.stream().mapToLong(DailyUsage::outputTokens).sum(),
            daily.stream().mapToLong(DailyUsage::totalTokens).sum(),
            daily.stream().mapToInt(DailyUsage::requests).sum(),
            daily.stream().map(DailyUsage::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add)
        );

        List<SystemUsageResponse.TopItem> topUserItems = topUsers.stream()
            .map(u -> new SystemUsageResponse.TopItem(
                u.userId(), u.userId(), u.totalRequestCount(), u.totalTokens()))
            .toList();

        return ResponseEntity.ok(new SystemUsageResponse(
            new DatePeriod(startDate, endDate),
            summary,
            daily,
            topUserItems,
            List.of() // topModels 待實作
        ));
    }

    /**
     * GET /api/v1/usage/users
     */
    @GetMapping("/users")
    public ResponseEntity<List<UserSummary>> getAllUsers() {
        return ResponseEntity.ok(queryService.getAllUsers());
    }

    /**
     * GET /api/v1/usage/users/{userId}
     */
    @GetMapping("/users/{userId}")
    public ResponseEntity<UserSummary> getUserSummary(@PathVariable String userId) {
        return queryService.getUserSummary(userId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
```

**驗收條件**:
- [ ] GET /api/v1/usage/users/{userId}/daily 正常
- [ ] GET /api/v1/usage/models/{model}/daily 正常
- [ ] GET /api/v1/usage/system/daily 正常
- [ ] GET /api/v1/usage/users 正常
- [ ] GET /api/v1/usage/users/{userId} 正常

---

### 任務 4.4：API 測試

**目標**: 測試 REST API 端點

**檔案**:
- `src/test/java/io/github/samzhu/ledger/controller/UsageApiControllerTest.java`

**內容**:
```java
package io.github.samzhu.ledger.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.github.samzhu.ledger.document.DailyUserUsage;
import io.github.samzhu.ledger.repository.DailyUserUsageRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class UsageApiControllerTest {

    @Container
    static MongoDBContainer mongodb = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongodb::getReplicaSetUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DailyUserUsageRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void shouldGetUserDailyUsage() throws Exception {
        // Given
        LocalDate today = LocalDate.now();
        String userId = "test-user";

        DailyUserUsage usage = new DailyUserUsage(
            DailyUserUsage.createId(today, userId),
            today, userId,
            1000, 500, 0, 0, 1500,
            10, 10, 0, 30000L,
            null, new BigDecimal("0.50"), Instant.now()
        );
        repository.save(usage);

        // When & Then
        mockMvc.perform(get("/api/v1/usage/users/{userId}/daily", userId)
                .param("startDate", today.toString())
                .param("endDate", today.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(userId))
            .andExpect(jsonPath("$.summary.totalTokens").value(1500))
            .andExpect(jsonPath("$.daily").isArray())
            .andExpect(jsonPath("$.daily[0].totalTokens").value(1500));
    }
}
```

**驗收條件**:
- [ ] 測試通過
- [ ] API 回應格式正確

---

## 階段 5：儀表板 UI

### 任務 5.1：配置 Tailwind CSS

**目標**: 設定 Tailwind CSS 與建構流程

**方式**: 使用 Tailwind CDN (簡化開發)

**檔案**:
- `src/main/resources/templates/layout/main.html`

**內容**:
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="zh-TW">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title th:text="${pageTitle} ?: 'Ledger - LLM 用量分析'">Ledger</title>

    <!-- Tailwind CSS CDN -->
    <script src="https://cdn.tailwindcss.com"></script>

    <!-- Chart.js -->
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>

    <!-- htmx -->
    <script src="https://unpkg.com/htmx.org@1.9.10"></script>

    <style>
        [x-cloak] { display: none !important; }
    </style>
</head>
<body class="bg-gray-100 min-h-screen">
    <!-- Sidebar -->
    <aside class="fixed inset-y-0 left-0 w-64 bg-gray-900 text-white">
        <div class="p-6">
            <h1 class="text-2xl font-bold">Ledger</h1>
            <p class="text-gray-400 text-sm">LLM 用量帳本</p>
        </div>
        <nav class="mt-6">
            <a th:href="@{/dashboard}"
               th:classappend="${currentPage == 'overview'} ? 'bg-gray-800' : ''"
               class="flex items-center px-6 py-3 hover:bg-gray-800 transition-colors">
                <span>系統總覽</span>
            </a>
            <a th:href="@{/dashboard/users}"
               th:classappend="${currentPage == 'users'} ? 'bg-gray-800' : ''"
               class="flex items-center px-6 py-3 hover:bg-gray-800 transition-colors">
                <span>用戶用量</span>
            </a>
            <a th:href="@{/dashboard/models}"
               th:classappend="${currentPage == 'models'} ? 'bg-gray-800' : ''"
               class="flex items-center px-6 py-3 hover:bg-gray-800 transition-colors">
                <span>模型用量</span>
            </a>
        </nav>
    </aside>

    <!-- Main Content -->
    <main class="ml-64 p-8">
        <div th:replace="${content}"></div>
    </main>
</body>
</html>
```

**驗收條件**:
- [ ] Tailwind 樣式正確載入
- [ ] 側邊欄導航正常

---

### 任務 5.2：建立 DashboardController

**目標**: 建立儀表板頁面控制器

**檔案**:
- `src/main/java/io/github/samzhu/ledger/controller/DashboardController.java`

**內容**:
```java
package io.github.samzhu.ledger.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import io.github.samzhu.ledger.document.DailyUserUsage;
import io.github.samzhu.ledger.document.SystemStats;
import io.github.samzhu.ledger.document.UserSummary;
import io.github.samzhu.ledger.service.UsageQueryService;

@Controller
@RequestMapping("/dashboard")
public class DashboardController {

    private final UsageQueryService queryService;

    public DashboardController(UsageQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 系統總覽
     */
    @GetMapping
    public String overview(Model model,
            @RequestParam(defaultValue = "7") int days) {

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days - 1);

        List<SystemStats> stats = queryService.getSystemDailyStats(startDate, endDate);
        List<UserSummary> topUsers = queryService.getTopUsers(10);

        model.addAttribute("currentPage", "overview");
        model.addAttribute("pageTitle", "系統總覽");
        model.addAttribute("stats", stats);
        model.addAttribute("topUsers", topUsers);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("days", days);

        return "dashboard/overview";
    }

    /**
     * 用戶列表
     */
    @GetMapping("/users")
    public String users(Model model) {
        List<UserSummary> users = queryService.getAllUsers();

        model.addAttribute("currentPage", "users");
        model.addAttribute("pageTitle", "用戶用量");
        model.addAttribute("users", users);

        return "dashboard/users";
    }

    /**
     * 用戶詳情
     */
    @GetMapping("/users/{userId}")
    public String userDetail(@PathVariable String userId,
            @RequestParam(defaultValue = "7") int days,
            Model model) {

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days - 1);

        List<DailyUserUsage> usages = queryService.getUserDailyUsage(userId, startDate, endDate);
        UserSummary summary = queryService.getUserSummary(userId).orElse(null);

        model.addAttribute("currentPage", "users");
        model.addAttribute("pageTitle", "用戶: " + userId);
        model.addAttribute("userId", userId);
        model.addAttribute("usages", usages);
        model.addAttribute("summary", summary);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("days", days);

        return "dashboard/user-detail";
    }

    /**
     * 模型用量
     */
    @GetMapping("/models")
    public String models(Model model,
            @RequestParam(defaultValue = "7") int days) {

        model.addAttribute("currentPage", "models");
        model.addAttribute("pageTitle", "模型用量");
        model.addAttribute("days", days);

        return "dashboard/models";
    }
}
```

**驗收條件**:
- [ ] /dashboard 頁面正常
- [ ] /dashboard/users 頁面正常
- [ ] /dashboard/users/{userId} 頁面正常

---

### 任務 5.3：建立系統總覽頁面

**目標**: 實作系統總覽儀表板

**檔案**:
- `src/main/resources/templates/dashboard/overview.html`

**內容**:
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout/main :: html(content=~{::content})}">
<body>
<div th:fragment="content">
    <!-- 標題與日期選擇 -->
    <div class="flex justify-between items-center mb-8">
        <h1 class="text-3xl font-bold text-gray-800">系統總覽</h1>
        <div class="flex gap-2">
            <a th:href="@{/dashboard(days=7)}"
               th:class="${days == 7} ? 'bg-blue-600 text-white' : 'bg-white text-gray-700'"
               class="px-4 py-2 rounded-lg shadow hover:shadow-md transition">
                7 天
            </a>
            <a th:href="@{/dashboard(days=14)}"
               th:class="${days == 14} ? 'bg-blue-600 text-white' : 'bg-white text-gray-700'"
               class="px-4 py-2 rounded-lg shadow hover:shadow-md transition">
                14 天
            </a>
            <a th:href="@{/dashboard(days=30)}"
               th:class="${days == 30} ? 'bg-blue-600 text-white' : 'bg-white text-gray-700'"
               class="px-4 py-2 rounded-lg shadow hover:shadow-md transition">
                30 天
            </a>
        </div>
    </div>

    <!-- 統計卡片 -->
    <div class="grid grid-cols-1 md:grid-cols-4 gap-6 mb-8">
        <div class="bg-white rounded-xl shadow p-6">
            <p class="text-gray-500 text-sm">總請求數</p>
            <p class="text-3xl font-bold text-gray-800"
               th:text="${#numbers.formatInteger(stats.stream().mapToInt(s -> s.totalRequestCount()).sum(), 0, 'COMMA')}">
                0
            </p>
        </div>
        <div class="bg-white rounded-xl shadow p-6">
            <p class="text-gray-500 text-sm">總 Token 數</p>
            <p class="text-3xl font-bold text-gray-800"
               th:text="${#numbers.formatInteger(stats.stream().mapToLong(s -> s.totalTokens()).sum(), 0, 'COMMA')}">
                0
            </p>
        </div>
        <div class="bg-white rounded-xl shadow p-6">
            <p class="text-gray-500 text-sm">預估成本</p>
            <p class="text-3xl font-bold text-green-600">
                $<span th:text="${#numbers.formatDecimal(stats.stream().map(s -> s.totalEstimatedCostUsd()).reduce(T(java.math.BigDecimal).ZERO, (a, b) -> a.add(b)), 1, 2)}">0.00</span>
            </p>
        </div>
        <div class="bg-white rounded-xl shadow p-6">
            <p class="text-gray-500 text-sm">活躍用戶</p>
            <p class="text-3xl font-bold text-gray-800" th:text="${topUsers.size()}">0</p>
        </div>
    </div>

    <!-- 趨勢圖 -->
    <div class="bg-white rounded-xl shadow p-6 mb-8">
        <h2 class="text-xl font-semibold mb-4">每日用量趨勢</h2>
        <canvas id="dailyChart" height="100"></canvas>
    </div>

    <!-- Top 用戶 -->
    <div class="bg-white rounded-xl shadow p-6">
        <h2 class="text-xl font-semibold mb-4">Top 10 用戶</h2>
        <table class="w-full">
            <thead class="bg-gray-50">
                <tr>
                    <th class="px-4 py-3 text-left text-sm font-medium text-gray-500">用戶</th>
                    <th class="px-4 py-3 text-right text-sm font-medium text-gray-500">請求數</th>
                    <th class="px-4 py-3 text-right text-sm font-medium text-gray-500">Token 數</th>
                    <th class="px-4 py-3 text-right text-sm font-medium text-gray-500">成本</th>
                </tr>
            </thead>
            <tbody class="divide-y divide-gray-200">
                <tr th:each="user : ${topUsers}" class="hover:bg-gray-50">
                    <td class="px-4 py-3">
                        <a th:href="@{/dashboard/users/{id}(id=${user.userId})}"
                           class="text-blue-600 hover:underline"
                           th:text="${user.userId}">user-id</a>
                    </td>
                    <td class="px-4 py-3 text-right"
                        th:text="${#numbers.formatInteger(user.totalRequestCount(), 0, 'COMMA')}">0</td>
                    <td class="px-4 py-3 text-right"
                        th:text="${#numbers.formatInteger(user.totalTokens(), 0, 'COMMA')}">0</td>
                    <td class="px-4 py-3 text-right text-green-600">
                        $<span th:text="${#numbers.formatDecimal(user.totalEstimatedCostUsd(), 1, 2)}">0.00</span>
                    </td>
                </tr>
            </tbody>
        </table>
    </div>

    <!-- Chart.js 初始化 -->
    <script th:inline="javascript">
        const stats = [[${stats}]];

        const labels = stats.map(s => s.date).sort();
        const tokenData = stats.sort((a, b) => a.date.localeCompare(b.date))
                               .map(s => s.totalTokens);
        const requestData = stats.sort((a, b) => a.date.localeCompare(b.date))
                                 .map(s => s.totalRequestCount);

        new Chart(document.getElementById('dailyChart'), {
            type: 'bar',
            data: {
                labels: labels,
                datasets: [
                    {
                        label: 'Token 數',
                        data: tokenData,
                        backgroundColor: 'rgba(59, 130, 246, 0.5)',
                        borderColor: 'rgb(59, 130, 246)',
                        borderWidth: 1
                    }
                ]
            },
            options: {
                responsive: true,
                scales: {
                    y: {
                        beginAtZero: true,
                        ticks: {
                            callback: function(value) {
                                return value.toLocaleString();
                            }
                        }
                    }
                }
            }
        });
    </script>
</div>
</body>
</html>
```

**驗收條件**:
- [ ] 統計卡片顯示正確
- [ ] 趨勢圖正常渲染
- [ ] Top 用戶表格正常

---

### 任務 5.4：建立用戶列表頁面

**目標**: 實作用戶列表頁面

**檔案**:
- `src/main/resources/templates/dashboard/users.html`

**內容**:
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout/main :: html(content=~{::content})}">
<body>
<div th:fragment="content">
    <h1 class="text-3xl font-bold text-gray-800 mb-8">用戶用量</h1>

    <div class="bg-white rounded-xl shadow">
        <table class="w-full">
            <thead class="bg-gray-50">
                <tr>
                    <th class="px-6 py-4 text-left text-sm font-medium text-gray-500">用戶 ID</th>
                    <th class="px-6 py-4 text-right text-sm font-medium text-gray-500">總請求數</th>
                    <th class="px-6 py-4 text-right text-sm font-medium text-gray-500">總 Token 數</th>
                    <th class="px-6 py-4 text-right text-sm font-medium text-gray-500">預估成本</th>
                    <th class="px-6 py-4 text-right text-sm font-medium text-gray-500">最後活動</th>
                </tr>
            </thead>
            <tbody class="divide-y divide-gray-200">
                <tr th:each="user : ${users}" class="hover:bg-gray-50">
                    <td class="px-6 py-4">
                        <a th:href="@{/dashboard/users/{id}(id=${user.userId})}"
                           class="text-blue-600 hover:underline font-medium"
                           th:text="${user.userId}">user-id</a>
                    </td>
                    <td class="px-6 py-4 text-right"
                        th:text="${#numbers.formatInteger(user.totalRequestCount(), 0, 'COMMA')}">0</td>
                    <td class="px-6 py-4 text-right"
                        th:text="${#numbers.formatInteger(user.totalTokens(), 0, 'COMMA')}">0</td>
                    <td class="px-6 py-4 text-right text-green-600">
                        $<span th:text="${#numbers.formatDecimal(user.totalEstimatedCostUsd(), 1, 2)}">0.00</span>
                    </td>
                    <td class="px-6 py-4 text-right text-gray-500"
                        th:text="${#temporals.format(user.lastActiveAt(), 'yyyy-MM-dd HH:mm')}">-</td>
                </tr>
                <tr th:if="${#lists.isEmpty(users)}">
                    <td colspan="5" class="px-6 py-8 text-center text-gray-500">
                        尚無用戶資料
                    </td>
                </tr>
            </tbody>
        </table>
    </div>
</div>
</body>
</html>
```

**驗收條件**:
- [ ] 用戶列表正確顯示
- [ ] 點擊用戶可進入詳情頁

---

### 任務 5.5：建立用戶詳情頁面

**目標**: 實作用戶詳情頁面 (含趨勢圖)

**檔案**:
- `src/main/resources/templates/dashboard/user-detail.html`

**內容**:
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout/main :: html(content=~{::content})}">
<body>
<div th:fragment="content">
    <!-- 麵包屑 -->
    <nav class="mb-4 text-sm text-gray-500">
        <a th:href="@{/dashboard/users}" class="hover:text-blue-600">用戶用量</a>
        <span class="mx-2">/</span>
        <span th:text="${userId}">user-id</span>
    </nav>

    <!-- 標題與日期選擇 -->
    <div class="flex justify-between items-center mb-8">
        <h1 class="text-3xl font-bold text-gray-800" th:text="${userId}">User</h1>
        <div class="flex gap-2">
            <a th:href="@{/dashboard/users/{id}(id=${userId}, days=7)}"
               th:class="${days == 7} ? 'bg-blue-600 text-white' : 'bg-white text-gray-700'"
               class="px-4 py-2 rounded-lg shadow hover:shadow-md transition">7 天</a>
            <a th:href="@{/dashboard/users/{id}(id=${userId}, days=14)}"
               th:class="${days == 14} ? 'bg-blue-600 text-white' : 'bg-white text-gray-700'"
               class="px-4 py-2 rounded-lg shadow hover:shadow-md transition">14 天</a>
            <a th:href="@{/dashboard/users/{id}(id=${userId}, days=30)}"
               th:class="${days == 30} ? 'bg-blue-600 text-white' : 'bg-white text-gray-700'"
               class="px-4 py-2 rounded-lg shadow hover:shadow-md transition">30 天</a>
        </div>
    </div>

    <!-- 統計卡片 -->
    <div class="grid grid-cols-1 md:grid-cols-4 gap-6 mb-8" th:if="${summary != null}">
        <div class="bg-white rounded-xl shadow p-6">
            <p class="text-gray-500 text-sm">總請求數</p>
            <p class="text-3xl font-bold text-gray-800"
               th:text="${#numbers.formatInteger(summary.totalRequestCount(), 0, 'COMMA')}">0</p>
        </div>
        <div class="bg-white rounded-xl shadow p-6">
            <p class="text-gray-500 text-sm">總 Token 數</p>
            <p class="text-3xl font-bold text-gray-800"
               th:text="${#numbers.formatInteger(summary.totalTokens(), 0, 'COMMA')}">0</p>
        </div>
        <div class="bg-white rounded-xl shadow p-6">
            <p class="text-gray-500 text-sm">預估成本</p>
            <p class="text-3xl font-bold text-green-600">
                $<span th:text="${#numbers.formatDecimal(summary.totalEstimatedCostUsd(), 1, 2)}">0.00</span>
            </p>
        </div>
        <div class="bg-white rounded-xl shadow p-6">
            <p class="text-gray-500 text-sm">首次使用</p>
            <p class="text-lg font-medium text-gray-800"
               th:text="${#temporals.format(summary.firstSeenAt(), 'yyyy-MM-dd')}">-</p>
        </div>
    </div>

    <!-- 趨勢圖 -->
    <div class="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-8">
        <div class="bg-white rounded-xl shadow p-6">
            <h2 class="text-xl font-semibold mb-4">Token 用量趨勢</h2>
            <canvas id="tokenChart" height="200"></canvas>
        </div>
        <div class="bg-white rounded-xl shadow p-6">
            <h2 class="text-xl font-semibold mb-4">請求數趨勢</h2>
            <canvas id="requestChart" height="200"></canvas>
        </div>
    </div>

    <!-- 每日明細 -->
    <div class="bg-white rounded-xl shadow p-6">
        <h2 class="text-xl font-semibold mb-4">每日明細</h2>
        <table class="w-full">
            <thead class="bg-gray-50">
                <tr>
                    <th class="px-4 py-3 text-left text-sm font-medium text-gray-500">日期</th>
                    <th class="px-4 py-3 text-right text-sm font-medium text-gray-500">請求數</th>
                    <th class="px-4 py-3 text-right text-sm font-medium text-gray-500">Input Tokens</th>
                    <th class="px-4 py-3 text-right text-sm font-medium text-gray-500">Output Tokens</th>
                    <th class="px-4 py-3 text-right text-sm font-medium text-gray-500">總 Tokens</th>
                    <th class="px-4 py-3 text-right text-sm font-medium text-gray-500">成本</th>
                </tr>
            </thead>
            <tbody class="divide-y divide-gray-200">
                <tr th:each="usage : ${usages}" class="hover:bg-gray-50">
                    <td class="px-4 py-3" th:text="${usage.date()}">-</td>
                    <td class="px-4 py-3 text-right"
                        th:text="${#numbers.formatInteger(usage.requestCount(), 0, 'COMMA')}">0</td>
                    <td class="px-4 py-3 text-right"
                        th:text="${#numbers.formatInteger(usage.totalInputTokens(), 0, 'COMMA')}">0</td>
                    <td class="px-4 py-3 text-right"
                        th:text="${#numbers.formatInteger(usage.totalOutputTokens(), 0, 'COMMA')}">0</td>
                    <td class="px-4 py-3 text-right"
                        th:text="${#numbers.formatInteger(usage.totalTokens(), 0, 'COMMA')}">0</td>
                    <td class="px-4 py-3 text-right text-green-600">
                        $<span th:text="${#numbers.formatDecimal(usage.estimatedCostUsd(), 1, 2)}">0.00</span>
                    </td>
                </tr>
            </tbody>
        </table>
    </div>

    <!-- Chart.js -->
    <script th:inline="javascript">
        const usages = [[${usages}]];

        const sortedUsages = usages.sort((a, b) => a.date.localeCompare(b.date));
        const labels = sortedUsages.map(u => u.date);
        const inputData = sortedUsages.map(u => u.totalInputTokens);
        const outputData = sortedUsages.map(u => u.totalOutputTokens);
        const requestData = sortedUsages.map(u => u.requestCount);

        // Token Chart
        new Chart(document.getElementById('tokenChart'), {
            type: 'bar',
            data: {
                labels: labels,
                datasets: [
                    {
                        label: 'Input Tokens',
                        data: inputData,
                        backgroundColor: 'rgba(59, 130, 246, 0.5)',
                        borderColor: 'rgb(59, 130, 246)',
                        borderWidth: 1
                    },
                    {
                        label: 'Output Tokens',
                        data: outputData,
                        backgroundColor: 'rgba(16, 185, 129, 0.5)',
                        borderColor: 'rgb(16, 185, 129)',
                        borderWidth: 1
                    }
                ]
            },
            options: {
                responsive: true,
                scales: { y: { beginAtZero: true, stacked: true }, x: { stacked: true } }
            }
        });

        // Request Chart
        new Chart(document.getElementById('requestChart'), {
            type: 'line',
            data: {
                labels: labels,
                datasets: [{
                    label: '請求數',
                    data: requestData,
                    borderColor: 'rgb(99, 102, 241)',
                    backgroundColor: 'rgba(99, 102, 241, 0.1)',
                    fill: true,
                    tension: 0.3
                }]
            },
            options: {
                responsive: true,
                scales: { y: { beginAtZero: true } }
            }
        });
    </script>
</div>
</body>
</html>
```

**驗收條件**:
- [ ] 用戶詳情正確顯示
- [ ] Token 趨勢圖正常
- [ ] 請求趨勢圖正常
- [ ] 日期切換正常

---

### 任務 5.6：建立模型用量頁面

**目標**: 實作模型用量頁面

**檔案**:
- `src/main/resources/templates/dashboard/models.html`

**內容**:
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout/main :: html(content=~{::content})}">
<body>
<div th:fragment="content">
    <h1 class="text-3xl font-bold text-gray-800 mb-8">模型用量</h1>

    <div class="bg-white rounded-xl shadow p-6">
        <p class="text-gray-500">模型用量分析功能開發中...</p>
        <!-- 待實作：類似用戶詳情頁的模型分析 -->
    </div>
</div>
</body>
</html>
```

**驗收條件**:
- [ ] 頁面可正常訪問
- [ ] 導航連結正常

---

## 階段 6：可觀測性與部署

### 任務 6.1：配置 Observability

**目標**: 配置 Micrometer Tracing

**檔案**:
- `src/main/java/io/github/samzhu/ledger/config/ObservabilityConfig.java`

**內容**:
```java
package io.github.samzhu.ledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;

@Configuration
public class ObservabilityConfig {

    @Bean
    public ObservedAspect observedAspect(ObservationRegistry registry) {
        return new ObservedAspect(registry);
    }
}
```

**更新 application.yaml**:
```yaml
management:
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: http://localhost:4318/v1/traces
```

**驗收條件**:
- [ ] Trace 正確發送到 Grafana LGTM
- [ ] 可在 Grafana 查看 Trace

---

### 任務 6.2：GraalVM Native Image 配置

**目標**: 配置 Native Image 支援

**檔案**:
- `src/main/java/io/github/samzhu/ledger/config/NativeImageHints.java`

**內容**:
```java
package io.github.samzhu.ledger.config;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

import io.github.samzhu.ledger.document.*;
import io.github.samzhu.ledger.dto.*;
import io.github.samzhu.ledger.dto.api.*;

@Configuration
@ImportRuntimeHints(NativeImageHints.Hints.class)
public class NativeImageHints {

    static class Hints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            // Documents
            hints.reflection().registerType(DailyUserUsage.class);
            hints.reflection().registerType(DailyModelUsage.class);
            hints.reflection().registerType(UserSummary.class);
            hints.reflection().registerType(SystemStats.class);

            // DTOs
            hints.reflection().registerType(UsageEvent.class);
            hints.reflection().registerType(UsageEventData.class);

            // API DTOs
            hints.reflection().registerType(UserUsageResponse.class);
            hints.reflection().registerType(ModelUsageResponse.class);
            hints.reflection().registerType(SystemUsageResponse.class);
        }
    }
}
```

**驗收條件**:
- [ ] `./gradlew nativeCompile` 成功
- [ ] Native binary 可正常啟動

---

### 任務 6.3：GCP 配置

**目標**: 配置 GCP 環境 (Pub/Sub + Firestore)

**檔案**:
- `src/main/resources/application-gcp.yaml`

**內容**:
```yaml
spring:
  cloud:
    gcp:
      core:
        enabled: true
      pubsub:
        enabled: true
    stream:
      default-binder: pubsub
      bindings:
        usageEventConsumer-in-0:
          binder: pubsub
          consumer:
            auto-create-resources: false
            ack-mode: AUTO
  data:
    mongodb:
      # Firestore MongoDB 相容 API
      uri: ${FIRESTORE_MONGODB_URI}

logging:
  level:
    root: WARN
    io.github.samzhu.ledger: INFO
```

**新增依賴 (build.gradle)**:
```groovy
// GCP
implementation 'com.google.cloud:spring-cloud-gcp-starter-pubsub'

ext {
    set('springCloudGcpVersion', "7.4.1")
}

dependencyManagement {
    imports {
        mavenBom "com.google.cloud:spring-cloud-gcp-dependencies:${springCloudGcpVersion}"
    }
}
```

**驗收條件**:
- [ ] GCP profile 配置完成
- [ ] Pub/Sub binder 可正常消費

---

### 任務 6.4：Cloud Run 部署配置

**目標**: 建立 Cloud Run 部署配置

**檔案**:
- `Dockerfile`

**內容**:
```dockerfile
# Build stage
FROM ghcr.io/graalvm/native-image-community:25 AS builder
WORKDIR /app
COPY . .
RUN ./gradlew nativeCompile --no-daemon

# Runtime stage
FROM debian:bookworm-slim
WORKDIR /app
COPY --from=builder /app/build/native/nativeCompile/ledger .

EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=gcp

ENTRYPOINT ["./ledger"]
```

**檔案**:
- `cloudbuild.yaml`

**內容**:
```yaml
steps:
  - name: 'gcr.io/cloud-builders/docker'
    args: ['build', '-t', 'gcr.io/$PROJECT_ID/ledger:$SHORT_SHA', '.']

  - name: 'gcr.io/cloud-builders/docker'
    args: ['push', 'gcr.io/$PROJECT_ID/ledger:$SHORT_SHA']

  - name: 'gcr.io/google.com/cloudsdktool/cloud-sdk'
    entrypoint: gcloud
    args:
      - 'run'
      - 'deploy'
      - 'ledger'
      - '--image=gcr.io/$PROJECT_ID/ledger:$SHORT_SHA'
      - '--region=asia-east1'
      - '--platform=managed'
      - '--memory=512Mi'
      - '--min-instances=1'
      - '--max-instances=3'

images:
  - 'gcr.io/$PROJECT_ID/ledger:$SHORT_SHA'
```

**驗收條件**:
- [ ] Docker build 成功
- [ ] Cloud Build 配置完成

---

### 任務 6.5：健康檢查與監控端點

**目標**: 確認 Actuator 端點正常

**驗證**:
```bash
# Liveness
curl http://localhost:8080/actuator/health/liveness

# Readiness
curl http://localhost:8080/actuator/health/readiness

# Metrics
curl http://localhost:8080/actuator/metrics

# Custom metric: buffer size
curl http://localhost:8080/actuator/metrics/ledger.buffer.size
```

**新增自訂 Metric (EventBufferService)**:
```java
@Service
public class EventBufferService implements SmartLifecycle {

    private final MeterRegistry meterRegistry;

    public EventBufferService(..., MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // 註冊 buffer size gauge
        Gauge.builder("ledger.buffer.size", eventBuffer, List::size)
            .description("Number of events in buffer")
            .register(meterRegistry);
    }
}
```

**驗收條件**:
- [ ] /actuator/health 回傳 UP
- [ ] /actuator/health/liveness 正常
- [ ] /actuator/health/readiness 正常
- [ ] ledger.buffer.size metric 可查詢

---

## 完成檢查清單

### 階段 1
- [ ] 1.1 Gradle 配置
- [ ] 1.2 主程式
- [ ] 1.3 Docker Compose
- [ ] 1.4 配置檔
- [ ] 1.5 配置屬性類別
- [ ] 1.6 MongoDB 配置

### 階段 2
- [ ] 2.1 DailyUserUsage
- [ ] 2.2 DailyModelUsage
- [ ] 2.3 UserSummary
- [ ] 2.4 SystemStats
- [ ] 2.5 Repository

### 階段 3
- [ ] 3.1 UsageEvent DTO
- [ ] 3.2 CostCalculationService
- [ ] 3.3 UsageAggregationService
- [ ] 3.4 EventBufferService
- [ ] 3.5 CloudEvents 消費者
- [ ] 3.6 整合測試

### 階段 4
- [ ] 4.1 UsageQueryService
- [ ] 4.2 API Response DTO
- [ ] 4.3 UsageApiController
- [ ] 4.4 API 測試

### 階段 5
- [ ] 5.1 Tailwind CSS 配置
- [ ] 5.2 DashboardController
- [ ] 5.3 系統總覽頁面
- [ ] 5.4 用戶列表頁面
- [ ] 5.5 用戶詳情頁面
- [ ] 5.6 模型用量頁面

### 階段 6
- [ ] 6.1 Observability 配置
- [ ] 6.2 Native Image 配置
- [ ] 6.3 GCP 配置
- [ ] 6.4 Cloud Run 部署
- [ ] 6.5 健康檢查

---

## 注意事項

1. **開發順序**: 嚴格按照階段順序進行，每個階段完成後再進入下一階段
2. **測試優先**: 每個任務完成後確認驗收條件
3. **Git 提交**: 每個任務完成後建議提交一次
4. **參考 Gate**: 可參考 Gate 專案的實作風格和配置方式

# Ledger 配置規劃

## 文件資訊
- **建立日期**: 2025-12-11
- **目的**: 規劃 Ledger 專案的配置架構,參考 Gate 專案的最佳實踐
- **參考**: Gate 專案的配置架構和部署經驗

---

## 一、配置設計原則

### 1.1 採用雙層 Profile 架構

參考 Gate 專案的成功經驗,採用「基礎設施層 + 行為層」的雙層 Profile 設計:

| 層級 | Profile | 職責 | 位置 |
|------|---------|------|------|
| **基礎設施層** | `local` | RabbitMQ、停用 GCP、本地 MongoDB | `src/main/resources/` |
| **基礎設施層** | `gcp` | Pub/Sub、Firestore、啟用 GCP | `src/main/resources/` |
| **行為層** | `dev` | 本地開發行為、DEBUG 日誌 | `config/` |
| **行為層** | `lab` | LAB 環境行為、全量取樣 | `config/` |
| **行為層** | `prod` | 生產環境行為、10% 取樣 | `config/` |

**啟動組合**:
- 本地開發: `local,dev` (預設)
- LAB 環境: `gcp,lab`
- 生產環境: `gcp,prod`

### 1.2 配置分層架構

```
┌─────────────────────────────────────────────────────────────────┐
│                     配置分層架構                                  │
├─────────────────────────────────────────────────────────────────┤
│  application.yaml (基礎配置)                                     │
│  ├── 所有環境共用的配置                                           │
│  ├── 使用 ${property:default} 提供預設值 (供 AOT 編譯)            │
│  └── 打包進 Docker Image                                         │
├─────────────────────────────────────────────────────────────────┤
│  基礎設施層 (src/main/resources/)                                │
│  ├── application-local.yaml: RabbitMQ、本地 MongoDB             │
│  └── application-gcp.yaml: Pub/Sub、Firestore MongoDB API       │
├─────────────────────────────────────────────────────────────────┤
│  行為層 (config/)                                                │
│  ├── application-dev.yaml: 本地開發行為                          │
│  ├── application-lab.yaml: LAB 環境行為                         │
│  └── application-prod.yaml: 生產環境行為                        │
└─────────────────────────────────────────────────────────────────┘
```

### 1.3 AOT 編譯配置 (必要)

參考 Gate 的 `application-aot.yaml`,確保 GraalVM Native Image AOT 編譯時:
- 所有 Binder (RabbitMQ, Pub/Sub) 都被正確註冊
- 不因 `enabled: false` 而導致 Binder 缺失

**決策**:
- Ledger 部署於 GCP Cloud Run,使用 Pub/Sub 和 Secret Manager,需要 AOT profile
- **與 Gate 一致**: 採用相同的 AOT 配置策略,確保 Native Image 建置正確

---

## 二、配置檔案規劃

### 2.1 application.yaml (基礎配置)

**調整重點**:
1. 修改預設 profiles 為 `local,dev`
2. 新增 AOT 友好的預設值
3. 啟用 Spring Observations (可觀測性)
4. 新增註解說明配置哲學

**差異分析 (與現有配置)**:

| 項目 | 現況 | 調整後 | 理由 |
|------|------|--------|------|
| `spring.profiles.default` | `local` | `local,dev` | 雙層 Profile 架構 |
| `spring.observations.annotations.enabled` | 無 | `true` | 啟用 @Observed 註解 |
| `spring.cloud.refresh.enabled` | 無 | `false` | AOT/Native Image 不支援 RefreshScope |
| `management.health.livenessstate.enabled` | 無 | `true` | Cloud Run 探針支援 |
| `management.health.readinessstate.enabled` | 無 | `true` | Cloud Run 探針支援 |
| `management.tracing.sampling.probability` | 無 | `1.0` | OpenTelemetry 取樣率 (環境覆蓋) |

**完整內容** (調整後):

```yaml
# =============================================================================
# Ledger - LLM 用量帳本服務 - 基礎共用配置
# =============================================================================
# 此檔案包進 Docker Image,包含所有環境共用的配置
# 環境特定配置透過 config/ 目錄下的 profile 檔案覆蓋
#
# 配置設計哲學:
#   - 雙層 Profile 架構: 基礎設施層 (local/gcp) + 行為層 (dev/lab/prod)
#   - AOT 友好: 所有屬性提供預設值,使用 ${property:default} 語法
#   - 結構與值分離: YAML 定義結構,值由環境提供
# =============================================================================

spring:
  application:
    name: ledger
  profiles:
    # 預設 profiles (本地開發)
    # 雙層架構: local (基礎設施) + dev (行為)
    default: local,dev
  # 啟用 Virtual Threads (Java 21+)
  # 對事件消費和聚合計算有顯著效能提升
  threads:
    virtual:
      enabled: true
  # 啟用 @Observed 註解支援 (預先配置,確保專案一致性)
  observations:
    annotations:
      enabled: true
  # 優雅關閉配置
  lifecycle:
    timeout-per-shutdown-phase: 30s
  cloud:
    # 禁用 RefreshScope - Spring Cloud RefreshScope 不支援 AOT/Native Image
    # 參考: https://github.com/spring-cloud/spring-cloud-release/wiki/AOT-transformations-and-native-image-support
    refresh:
      enabled: false
    # Spring Cloud Stream 配置
    # Function Bean 名稱: usageEventConsumer
    # 自動綁定名稱: usageEventConsumer-in-0
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
    size: 100           # 累積 100 筆後批量寫入
    interval-ms: 5000   # 或每 5 秒寫入一次
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

# Actuator 配置
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true
  # OpenTelemetry 配置 (Spring Boot 3.x)
  # 取樣率由環境 profile 控制 (lab=1.0, prod=0.1)
  # 本地開發: Docker Compose 自動偵測 grafana-lgtm 容器並配置 OTLP 端點
  # 部署環境: 設定業界標準環境變數 OTEL_EXPORTER_OTLP_ENDPOINT
  tracing:
    sampling:
      probability: 1.0

logging:
  level:
    root: INFO
    io.github.samzhu.ledger: DEBUG
```

---

### 2.2 application-local.yaml (本地基礎設施)

**調整重點**:
1. 明確停用 GCP 功能 (使用 `enabled: false` 而非 `autoconfigure.exclude`)
2. 指定 RabbitMQ binder
3. 新增註解說明

**完整內容** (調整後):

```yaml
# =============================================================================
# Ledger - 本地開發基礎設施
# =============================================================================
# Profile: local
# 用途: 本地開發環境基礎設施,使用 RabbitMQ + 本地 MongoDB
#
# 此 profile 只負責基礎設施配置 (MQ binder, DB connection)
# 開發行為設定由 dev profile 處理
#
# 重要: 不要使用 spring.autoconfigure.exclude 排除 GCP 類別
#       這會導致 GraalVM Native Image AOT 編譯時無法註冊 Pub/Sub Binder
#       改用 spring.cloud.gcp.*.enabled=false 來禁用功能
# =============================================================================

spring:
  # MongoDB 配置 (本地 Docker Compose)
  data:
    mongodb:
      uri: ${MONGODB_URI:mongodb://root:secret@localhost:27017/ledger?authSource=admin}
  # RabbitMQ 配置 (本地 Docker Compose)
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME:guest}
    password: ${RABBITMQ_PASSWORD:guest}
  cloud:
    # 停用 GCP 功能 (透過 property 而非 autoconfigure.exclude)
    # 這樣 AOT 編譯時仍會註冊 Pub/Sub Binder,但本地開發時不會啟用
    gcp:
      core:
        enabled: false
      pubsub:
        enabled: false
    stream:
      # 使用 RabbitMQ binder
      default-binder: rabbit
      bindings:
        usageEventConsumer-in-0:
          binder: rabbit
  # 開發時關閉 Thymeleaf 快取
  thymeleaf:
    cache: false

# 停用 GCP 健康檢查 (本地使用 RabbitMQ)
management:
  health:
    gcp:
      enabled: false
```

---

### 2.3 application-gcp.yaml (GCP 基礎設施) - **新增**

**建立原因**:
- 統一 GCP 環境的基礎設施配置
- 啟用 Pub/Sub Binder
- 啟用 Secret Manager 整合 (`spring.config.import: sm@`)
- 配置 Firestore MongoDB API 連接

**完整內容**:

```yaml
# =============================================================================
# Ledger - GCP 基礎設施
# =============================================================================
# Profile: gcp
# 用途: GCP 雲端環境，使用 Pub/Sub + Firestore (MongoDB API)
#
# GCP 配置說明:
#   - project-id: 自動從 GCP 環境取得，無需手動設定
#   - Topic 名稱: llm-gateway-usage (與 Gate 一致)
#
# Secret Manager 整合:
#   - 使用 sm@ 語法從 GCP Secret Manager 讀取機敏值
#   - 語法: ${sm@secret-name} 或 ${sm@project-id/secret-name/version}
#   - 參考: https://googlecloudplatform.github.io/spring-cloud-gcp/7.4.1/reference/html/index.html#secret-manager
#
# Firestore MongoDB API:
#   - 使用 Firestore 的 MongoDB 相容 API
#   - 連接字串格式: mongodb://{project-id}:{database-id}@firestore.googleapis.com:443/...
#   - 認證: MONGODB-OIDC (自動從 GCP 環境取得)
#
# 可觀測性說明 (OpenTelemetry Collector Sidecar):
#   - Cloud Run 部署 OTel Collector 作為 Sidecar 容器
#   - 應用程式發送 OTLP 到 localhost:4317 (gRPC) 或 localhost:4318 (HTTP)
#   - Collector 自動導出到 Cloud Trace / Cloud Monitoring / Cloud Logging
#   - 參考: https://cloud.google.com/stackdriver/docs/instrumentation/opentelemetry-collector-cloud-run
# =============================================================================

spring:
  # 啟用 Secret Manager Config Data API (所有 GCP 環境共用)
  config:
    import: sm@
  # MongoDB 配置 (GCP Firestore MongoDB 相容 API)
  data:
    mongodb:
      # Firestore MongoDB 相容 API 連接字串
      # 格式: mongodb://{project-id}:{database-id}@firestore.googleapis.com:443/?ssl=true&authMechanism=MONGODB-OIDC&authMechanismProperties=ENVIRONMENT:GCP
      # project-id: 自動從 GCP 環境取得
      # database-id: 預設為 (default)，可透過環境變數覆蓋
      uri: mongodb://${GCP_PROJECT_ID}:${FIRESTORE_DATABASE_ID:(default)}@firestore.googleapis.com:443/?ssl=true&authMechanism=MONGODB-OIDC&authMechanismProperties=ENVIRONMENT:GCP
  cloud:
    # 明確啟用 GCP 功能 (覆蓋 local profile 的 enabled: false)
    gcp:
      core:
        enabled: true
      pubsub:
        enabled: true
      secretmanager:
        enabled: true
    stream:
      bindings:
        usageEventConsumer-in-0:
          binder: pubsub
          consumer:
            auto-create-resources: false
            ack-mode: AUTO
      binders:
        pubsub:
          type: pubsub

# GCP 環境 OTLP 配置
# 發送到 Sidecar Collector (localhost)，由 Collector 導出到 GCP 服務
management:
  health:
    # 停用 RabbitMQ 健康檢查 (GCP 使用 Pub/Sub，不需要 RabbitMQ)
    rabbit:
      enabled: false
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/traces
    logging:
      export:
        otlp:
          endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/logs
  otlp:
    metrics:
      export:
        url: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/metrics
```

---

### 2.4 config/application-dev.yaml (本地開發行為) - **新增**

**建立原因**:
- 分離「基礎設施」和「行為」配置
- 本地開發專用的日誌、監控設定

**完整內容**:

```yaml
# =============================================================================
# DEV 環境配置 - 本地開發環境行為設定
# =============================================================================
# 用途: 本地開發環境
# 預設: spring.profiles.default=local,dev (自動啟用)
#
# 機敏值來源: config/application-secrets.properties
# 屬性名稱與 GCP Secret Manager 一致，便於管理:
#   - 本地: ${ledger-xxx} 從 properties 檔案讀取
#   - GCP:  ${sm@ledger-xxx} 從 Secret Manager 讀取
# =============================================================================

spring:
  config:
    # 載入本地機敏設定 (optional: 檔案不存在也不報錯)
    import: "optional:file:./config/application-secrets.properties"

# 批量處理配置 (本地開發可減少批量大小以便測試)
ledger:
  batch:
    size: 10           # 本地開發: 累積 10 筆就寫入 (便於測試)
    interval-ms: 2000  # 本地開發: 每 2 秒寫入 (便於測試)

# 可觀測性 - 全量取樣便於除錯
management:
  tracing:
    sampling:
      probability: 1.0
  endpoints:
    web:
      exposure:
        # 本地開發: 暴露所有端點 (包含 env, configprops)
        include: health,info,metrics,prometheus,env,configprops

# 日誌 - DEBUG 便於開發
logging:
  level:
    root: INFO
    io.github.samzhu.ledger: DEBUG
    org.springframework.data.mongodb: DEBUG
    org.springframework.cloud.stream: DEBUG
```

---

### 2.5 config/application-lab.yaml (LAB 環境行為) - **新增**

**建立原因**:
- LAB 環境專用的行為配置
- 與生產環境隔離,允許更詳細的日誌和全量取樣

**完整內容**:

```yaml
# =============================================================================
# LAB 環境配置
# =============================================================================
# 用途: 實驗/開發環境 (部署於 GCP Cloud Run)
# 啟動: spring.profiles.active=gcp,lab
#
# 部署方式:
#   1. 此檔案內容存放於 GCP Secret Manager (secret: ledger-config)
#   2. Cloud Run 掛載到 /config/application-lab.yaml
#   3. Spring Boot 透過 SPRING_CONFIG_ADDITIONAL_LOCATION=file:/config/ 載入
#
# Secret 名稱說明:
#   每個 GCP Project 為獨立環境，因此 secret 名稱不需環境後綴
#   例如：vibe-lab 專案和 vibe-prod 專案都使用 "ledger-config" 名稱
#
# Secret Manager 整合:
#   application-gcp.yaml 已啟用 spring.config.import: sm@
#   因此 ${sm@secret-name} 語法可用於引用其他 secrets
#
# 必要的 Secrets (需先在 GCP Secret Manager 建立):
#   - ledger-config: 此配置檔內容
#   - (未來如需機敏配置可在此新增)
# =============================================================================

# 機敏屬性來源 - 從 Secret Manager 讀取，覆蓋 application.yaml 的預設值
# 範例 (未來如需機敏配置):
# ledger-mongodb-uri: ${sm@ledger-mongodb-uri}

# 可觀測性 - 全量取樣便於除錯
management:
  tracing:
    sampling:
      probability: 1.0
  endpoints:
    web:
      exposure:
        # 注意: 移除 env,configprops 避免洩漏 secrets
        include: health,info,metrics,prometheus

# 日誌 - DEBUG 便於開發
logging:
  level:
    root: INFO
    io.github.samzhu.ledger: DEBUG
```

---

### 2.6 config/application-prod.yaml (生產環境行為) - **新增**

**建立原因**:
- 生產環境專用的行為配置
- 降低日誌等級、減少取樣率

**完整內容**:

```yaml
# =============================================================================
# PROD 環境配置
# =============================================================================
# 用途: 生產環境 (部署於 GCP Cloud Run)
# 啟動: SPRING_PROFILES_ACTIVE=gcp,prod
#
# 部署方式:
#   1. 此檔案內容存放於 GCP Secret Manager (secret: ledger-config)
#   2. Cloud Run 掛載到 /config/application-prod.yaml
#   3. Spring Boot 透過 SPRING_CONFIG_ADDITIONAL_LOCATION=file:/config/ 載入
# =============================================================================

# 批量處理配置 (生產環境可能需要更大的批量以提升效能)
ledger:
  batch:
    size: 200          # 生產環境: 累積 200 筆再寫入
    interval-ms: 10000 # 生產環境: 每 10 秒寫入

# 可觀測性 - 10% 取樣減少開銷
management:
  tracing:
    sampling:
      probability: 0.1
  endpoints:
    web:
      exposure:
        # 生產環境: 只暴露必要端點
        include: health,info,metrics,prometheus

# 日誌 - INFO 減少日誌量
logging:
  level:
    root: INFO
    io.github.samzhu.ledger: INFO
```

---

### 2.7 application-aot.yaml (AOT 編譯配置) - **必要**

**決策**:

**需要新增**,原因:
1. Ledger 部署於 GCP Cloud Run,使用 Pub/Sub 和 Secret Manager
2. 需要確保 AOT 編譯時 RabbitMQ 和 Pub/Sub Binder 都被正確註冊
3. 與 Gate 專案保持一致的 AOT 配置策略

**完整內容**:

```yaml
# =============================================================================
# Ledger - AOT 編譯專用配置
# =============================================================================
# Profile: aot
# 用途: GraalVM Native Image AOT 編譯時使用
#
# 重要說明:
#   - 此 profile 確保所有 binders (RabbitMQ + Pub/Sub) 在 AOT 編譯時都被註冊
#   - 不要在執行時使用此 profile
#   - 執行時應使用 local (開發) 或 gcp (生產) profile
# =============================================================================

spring:
  cloud:
    # 啟用 GCP 功能讓 Pub/Sub Binder 被 AOT 處理
    gcp:
      core:
        enabled: true
      pubsub:
        enabled: true
      # 停用 Secret Manager (AOT 編譯時不需要)
      secretmanager:
        enabled: false
    stream:
      # 配置兩個 binders，讓 AOT 都能處理
      binders:
        rabbit:
          type: rabbit
        pubsub:
          type: pubsub
```

---

## 三、配置檔案清單

### 3.1 需要調整的檔案

| 檔案路徑 | 狀態 | 說明 |
|---------|------|------|
| `src/main/resources/application.yaml` | **更新** | 新增註解、調整預設 profiles、啟用 Observations |
| `src/main/resources/application-local.yaml` | **更新** | 新增註解、明確停用 GCP、停用 GCP 健康檢查 |

### 3.2 需要新增的檔案

| 檔案路徑 | 狀態 | 說明 |
|---------|------|------|
| `src/main/resources/application-gcp.yaml` | **新增** | GCP 基礎設施配置 |
| `config/application-dev.yaml` | **新增** | 本地開發行為配置 |
| `config/application-lab.yaml` | **新增** | LAB 環境行為配置 |
| `config/application-prod.yaml` | **新增** | 生產環境行為配置 |
| `config/README.md` | **新增** | 說明 config 目錄用途 |

### 3.3 AOT 編譯配置

| 檔案路徑 | 狀態 | 說明 |
|---------|------|------|
| `src/main/resources/application-aot.yaml` | **新增** | GCP Cloud Run 部署必要,確保 Binder 正確註冊 |

---

## 四、config 目錄說明文件

建議建立 `config/README.md` 說明外部配置目錄:

```markdown
# Config 目錄

此目錄包含環境特定的**行為層**配置檔案。

## Profile 架構

Ledger 使用雙層 Profile 架構:

| 層級 | Profile | 位置 | 說明 |
|------|---------|------|------|
| 基礎設施層 | `local` / `gcp` | `src/main/resources/` | 基礎設施配置 (MQ, DB) |
| 行為層 | `dev` / `lab` / `prod` | `config/` | 環境行為配置 (日誌、監控) |

## 環境組合

- **本地開發**: `local,dev` (預設)
- **LAB 環境**: `gcp,lab`
- **生產環境**: `gcp,prod`

## 檔案列表

| 檔案 | 用途 | Git 狀態 |
|------|------|----------|
| `application-dev.yaml` | 本地開發行為 | 追蹤 |
| `application-lab.yaml` | LAB 環境行為 | 追蹤 |
| `application-prod.yaml` | 生產環境行為 | 追蹤 |

## 本地開發使用

Spring Boot 會自動載入 `config/` 目錄 (相對於執行目錄):

```bash
# 本地開發 (預設)
./gradlew bootRun

# 或指定 profiles
./gradlew bootRun --args='--spring.profiles.active=local,dev'
```

## GCP 部署使用

Cloud Run 部署時:
1. `application-lab.yaml` / `application-prod.yaml` 存放於 Secret Manager
2. 掛載到 `/config/application-{env}.yaml`
3. 設定環境變數:
   - `SPRING_PROFILES_ACTIVE=gcp,lab` (或 `gcp,prod`)
   - `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/config/`

## 未來擴展

如需新增機敏配置 (如資料庫密碼):

1. **本地開發**: 建立 `config/application-secrets.properties` (不進版控)
2. **GCP 環境**: 使用 Secret Manager + `sm@` 語法

參考 Gate 專案的機敏值處理模式。
```

---

## 五、環境變數清單

### 5.1 本地開發環境

| 變數名稱 | 預設值 | 說明 |
|---------|--------|------|
| `SPRING_PROFILES_ACTIVE` | `local,dev` | Profile 組合 |
| `MONGODB_URI` | `mongodb://root:secret@localhost:27017/ledger?authSource=admin` | MongoDB 連接字串 |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ 主機 |
| `RABBITMQ_PORT` | `5672` | RabbitMQ 埠號 |
| `RABBITMQ_USERNAME` | `guest` | RabbitMQ 使用者名稱 |
| `RABBITMQ_PASSWORD` | `guest` | RabbitMQ 密碼 |

### 5.2 GCP LAB 環境

| 變數名稱 | 預設值 | 說明 |
|---------|--------|------|
| `SPRING_PROFILES_ACTIVE` | `gcp,lab` | Profile 組合 |
| `SPRING_CONFIG_ADDITIONAL_LOCATION` | `file:/config/` | 外部配置位置 |
| `GCP_PROJECT_ID` | (自動偵測) | GCP 專案 ID |
| `FIRESTORE_DATABASE_ID` | `(default)` | Firestore 資料庫 ID |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | OTLP Collector 端點 |

### 5.3 GCP PROD 環境

同 LAB 環境,但 `SPRING_PROFILES_ACTIVE=gcp,prod`

---

## 六、與 Gate 專案的差異

| 項目 | Gate | Ledger | 說明 |
|------|------|--------|------|
| **機敏配置** | 有 (JWT, API Key) | **較少** | Ledger 主要為資料庫連接 |
| **Secret Manager** | 必須 | **必須** | 部署於 GCP Cloud Run,需要 Secret Manager |
| **application-secrets.properties** | 需要 | **需要** | 本地開發用,保護機敏值 |
| **AOT profile** | 需要 | **需要** | 部署於 GCP Cloud Run,需要正確註冊 Binder |
| **配置複雜度** | 高 (多個機敏值) | **中** (較少機敏值) |

---

## 七、實施步驟

### Step 1: 調整現有檔案

1. 更新 `src/main/resources/application.yaml`
2. 更新 `src/main/resources/application-local.yaml`

### Step 2: 新增 GCP 基礎設施配置

3. 建立 `src/main/resources/application-gcp.yaml`
4. 建立 `src/main/resources/application-aot.yaml`

### Step 3: 建立 config 目錄結構

5. 建立 `config/` 目錄
6. 建立 `config/application-dev.yaml`
7. 建立 `config/application-lab.yaml`
8. 建立 `config/application-prod.yaml`
9. 建立 `config/README.md`

### Step 4: 測試本地開發

10. 啟動 Docker Compose: `docker compose up -d`
11. 啟動應用程式: `./gradlew bootRun`
12. 驗證 profiles 為 `local,dev`: 檢查日誌中的 "The following profiles are active: local,dev"
13. 測試 Actuator: `curl http://localhost:8080/actuator/health`

### Step 5: 更新 .gitignore (如需)

如未來新增機敏配置:
```gitignore
# 機敏配置 (本地開發用)
config/application-secrets.properties
```

---

## 八、驗證檢查清單

### 本地開發驗證

- [ ] 應用程式以 `local,dev` profiles 啟動
- [ ] 連接到本地 MongoDB 成功
- [ ] 連接到本地 RabbitMQ 成功
- [ ] Actuator health 端點回傳 UP
- [ ] 日誌等級為 DEBUG (`io.github.samzhu.ledger`)

### AOT 編譯驗證

- [ ] `./gradlew bootBuildImage` 成功
- [ ] 無 "PlaceholderResolutionException" 錯誤
- [ ] 生成的 Native Image 可啟動

### 配置架構驗證

- [ ] 所有配置檔案有清楚的註解說明
- [ ] Profile 組合邏輯清晰 (`local,dev` / `gcp,lab`)
- [ ] 無硬編碼的機敏值
- [ ] 所有環境變數有預設值

---

## 九、未來考量

### 9.1 如需新增機敏配置

當未來需要保護資料庫連接字串或其他機敏資訊時:

1. **本地開發**:
   - 建立 `config/application-secrets.properties`
   - 在 `application-dev.yaml` 新增 `spring.config.import: optional:file:./config/application-secrets.properties`

2. **GCP 環境**:
   - 在 `application-gcp.yaml` 啟用 `spring.config.import: sm@`
   - 在環境配置檔使用 `${sm@secret-name}` 語法

3. 參考 Gate 專案的實作:
   - `docs/DEVELOPMENT-NOTES.md` 第 3-5 節
   - `docs/DEVELOPMENT-PLAN.md` 配置設計哲學

### 9.2 AOT 編譯問題應對

如遇到 AOT 編譯錯誤 "PlaceholderResolutionException":

1. 檢查所有 `${property}` 是否有預設值 `${property:default}`
2. 確認 `application-aot.yaml` 已正確配置 (參考本文 2.7 節)
3. 確認 CI 建置時設定 `SPRING_PROFILES_ACTIVE=aot`
4. 參考 Gate 專案的 `docs/DEVELOPMENT-NOTES.md` 第 1 節

### 9.3 Pub/Sub 整合測試 (可選)

當未來需要更真實的 Pub/Sub 整合測試時 (如測試訊息確認、重試邏輯)，可加入 GCP 模擬器：

1. **新增依賴**:
   ```groovy
   testImplementation 'org.testcontainers:gcloud'
   ```

2. **測試範例**:
   ```java
   @Container
   private static final PubSubEmulatorContainer pubsubEmulator =
       new PubSubEmulatorContainer(
           DockerImageName.parse("gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators"));

   @DynamicPropertySource
   static void emulatorProperties(DynamicPropertyRegistry registry) {
       registry.add("spring.cloud.gcp.pubsub.emulator-host",
           pubsubEmulator::getEmulatorEndpoint);
   }
   ```

3. **參考文件**:
   - [Testcontainers GCloud Module](https://java.testcontainers.org/modules/gcloud/)
   - [Google Cloud Emulators with Spring Boot](https://cloud.google.com/blog/products/application-development/develop-and-test-spring-boot-applications-consistently)

**目前狀態**: 使用 `spring-cloud-stream-test-binder` 已足夠測試訊息消費邏輯

---

## 十、參考資料

- Gate 專案配置架構: `/Users/samzhu/workspace/github-samzhu/gate/config/`
- Gate 開發注意事項: `/Users/samzhu/workspace/github-samzhu/gate/docs/DEVELOPMENT-NOTES.md`
- Spring Boot External Config: https://docs.spring.io/spring-boot/reference/features/external-config.html
- Spring Cloud GCP: https://googlecloudplatform.github.io/spring-cloud-gcp/reference/html/index.html
- GraalVM Native Image: https://docs.spring.io/spring-boot/reference/packaging/native-image/index.html

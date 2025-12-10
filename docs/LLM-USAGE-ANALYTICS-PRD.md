# Ledger: LLM 用量帳本服務 - 產品需求文件

## 文件資訊
- **版本**: 1.2.0
- **建立日期**: 2025-12-09
- **最後更新**: 2025-12-09
- **專案名稱**: `ledger`
- **完整名稱**: LLM Usage Ledger
- **技術堆疊**: Spring Boot 3.5.8, Spring Data MongoDB, Spring Cloud Stream, Java 25, GraalVM Native
- **目標平台**: LLM 用量分析與報表服務
- **上游系統**: Gate (LLM Gateway) - 透過 GCP Pub/Sub 發送 CloudEvents 用量事件

### 命名說明

| 服務 | 名稱 | 角色 |
|------|------|------|
| **Gate** | 閘門 | LLM API 入口，流量控制與用量追蹤 |
| **Ledger** | 帳本 | 用量記錄、統計分析與成本歸屬 |

### 技術選型說明

| 元件 | 選擇 | 說明 |
|------|------|------|
| **Spring Boot** | 3.5.8 | 目前最新穩定版本 |
| **資料庫存取** | Spring Data MongoDB | 透過 Firestore MongoDB 相容 API 存取 |
| **訊息消費** | Spring Cloud Stream | 抽象層，本地用 RabbitMQ、GCP 用 Pub/Sub Binder |
| **可觀測性** | Micrometer + Brave | Spring Boot 3.x 推薦配置 |
| **排程任務** | 不在此服務 | 資料清理等排程由獨立服務或 Cloud Scheduler 處理 |

---

## 1. 執行摘要

**LLM Usage Analytics** 是一個專門用於接收、儲存和分析 LLM Token 用量事件的服務。它從 Gate (LLM Gateway) 透過 GCP Pub/Sub 接收 CloudEvents 格式的用量事件，以成本最佳化的方式儲存到 GCP Firestore，並提供內嵌網頁進行用量分析和視覺化。

### 核心價值主張
- **成本最佳化儲存**: 針對 Firestore 計費模式設計，使用預聚合策略減少讀取次數
- **即時用量追蹤**: 接收 Pub/Sub 事件並即時更新聚合統計
- **多維度分析**: 支援用戶、模型、日期等維度的用量分析
- **內嵌分析網頁**: 提供直覺的用量報表和視覺化介面
- **GraalVM Native**: 快速啟動、低記憶體佔用，適合 Cloud Run 部署

---

## 2. 背景與動機

### 2.1 問題陳述

Gate (LLM Gateway) 負責追蹤每次 API 呼叫的 Token 用量，並透過 CloudEvents 發送到 GCP Pub/Sub。但這些事件需要被持久化儲存和分析，才能實現：

1. **成本歸屬**: 追蹤各用戶/團隊的 LLM 使用成本
2. **用量監控**: 監控 Token 消耗趨勢和異常
3. **配額管理**: 為未來的用量配額功能提供資料基礎
4. **報表分析**: 提供管理者和用戶查看用量報表

### 2.2 為什麼選擇 GCP Firestore + MongoDB Driver

| 考量因素 | 說明 |
|----------|------|
| **Serverless** | 無需管理基礎設施，自動擴展 |
| **GCP 整合** | 與 Pub/Sub、Cloud Run 無縫整合 |
| **成本模型** | 按使用量計費，適合中小規模 |
| **NoSQL 彈性** | Schema-less，適合演進中的資料結構 |
| **MongoDB 相容** | 使用熟悉的 MongoDB Driver API，降低學習曲線 |
| **可移植性** | 未來可無縫遷移到 MongoDB Atlas 或自建 MongoDB |

#### 為什麼使用 MongoDB Driver 而非 Firestore SDK

| 比較項目 | MongoDB Driver | Firestore SDK |
|----------|----------------|---------------|
| **API 熟悉度** | 廣泛使用的標準 API | Google 專有 API |
| **生態系統** | Spring Data MongoDB 整合 | 需要 Spring Cloud GCP |
| **可移植性** | 可遷移到任何 MongoDB 相容資料庫 | 僅限 Firestore |
| **原子操作** | `$inc` 操作符 | `FieldValue.increment()` |
| **批量操作** | `bulkWrite()` | `WriteBatch` |

> **GCP Firestore MongoDB 相容性**: Firestore 提供 MongoDB 相容 API，可透過標準 MongoDB 驅動連接。
> 連接字串格式: `mongodb://firestore.googleapis.com:443/?authMechanism=MONGODB-OIDC&authMechanismProperties=ENVIRONMENT:GCP`

### 2.3 系統架構定位

```
┌─────────────┐     ┌─────────────┐     ┌─────────────────────┐     ┌─────────────┐
│ Claude Code │────▶│    Gate     │────▶│    GCP Pub/Sub      │────▶│   Usage     │
│    CLI      │     │ (Gateway)   │     │ llm-gateway-usage   │     │  Analytics  │
└─────────────┘     └─────────────┘     └─────────────────────┘     └──────┬──────┘
                                                                           │
                                                                           ▼
                                                                    ┌─────────────┐
                                                                    │  Firestore  │
                                                                    │  (Native)   │
                                                                    └──────┬──────┘
                                                                           │
                                                                    ┌──────▼──────┐
                                                                    │   內嵌網頁   │
                                                                    │  用量報表    │
                                                                    └─────────────┘
```

---

## 3. GCP Firestore 計費模式與最佳化策略

### 3.1 Firestore 計費項目

| 計費項目 | 價格 (美國區域) | 說明 |
|----------|----------------|------|
| 文件讀取 | $0.036 / 100,000 次 | 每次查詢的文件數計入 |
| 文件寫入 | $0.108 / 100,000 次 | 新增、更新都算寫入 |
| 文件刪除 | $0.012 / 100,000 次 | 刪除文件計入 |
| 儲存空間 | $0.108 / GiB / 月 | 所有文件的總儲存量 |
| 網路輸出 | $0.12 / GiB | 跨區域資料傳輸 |

> **關鍵洞察**: 讀取成本是寫入成本的 1/3，但分析場景讀取次數遠高於寫入次數。

### 3.2 成本最佳化策略

#### 策略 1: 預聚合 (Pre-Aggregation)

**問題**: 每次查看用戶每日用量需要讀取該用戶當天所有事件文件。

**解決方案**: 在寫入原始事件時，同時更新預聚合文件。

```
┌────────────────────────────────────────────────────────────────┐
│                    寫入時預聚合                                  │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  CloudEvent                                                    │
│  (用量事件)    ──────┬──────▶  原始事件 (可選保留)              │
│                     │                                          │
│                     ├──────▶  每日用戶聚合 (更新)              │
│                     │         daily_user_usage/{date}_{userId} │
│                     │                                          │
│                     ├──────▶  每日模型聚合 (更新)              │
│                     │         daily_model_usage/{date}_{model} │
│                     │                                          │
│                     └──────▶  用戶總計 (更新)                  │
│                               user_summary/{userId}            │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

**成本效益**:
- 查看某用戶某天用量: 1 次讀取 (vs 原本 N 次)
- 查看某模型某天用量: 1 次讀取 (vs 原本 M 次)

#### 策略 2: 批量寫入 (Batch Writes)

**問題**: 高頻事件導致大量寫入操作。

**解決方案**: 在記憶體中累積事件，定期批量寫入 Firestore。

```java
// 批量寫入配置
usage-analytics:
  batch:
    size: 100          # 累積 100 筆後批量寫入
    interval-ms: 5000  # 或每 5 秒寫入一次
```

**成本效益**:
- Firestore 批量寫入最多 500 筆/次，每筆仍算一次寫入
- 但可減少網路往返和連線開銷
- 搭配聚合更新，可在同一批次中合併多次對同一聚合文件的更新

#### 策略 3: 冷熱資料分層

**問題**: 歷史原始事件很少被查詢，但佔用儲存空間。

**解決方案**: 分層儲存策略。

| 資料類型 | 保留策略 | 說明 |
|----------|----------|------|
| **聚合資料** | 永久保留 | 每日/用戶/模型聚合，查詢頻繁 |
| **原始事件** | 30 天 | 用於異常排查和審計 |
| **詳細原始事件** | 可選不保留 | 只保留聚合資料以節省成本 |

#### 策略 4: 查詢最佳化

**原則**: 避免集合掃描，使用文件 ID 直接存取。

```
良好設計（直接 ID 存取）:
daily_user_usage/{date}_{userId}
→ 查詢特定用戶特定日期: 1 次讀取

不良設計（需要查詢）:
daily_usage/{autoId}
→ 需要 WHERE date=X AND userId=Y: N 次讀取
```

### 3.3 預估成本試算

**假設場景**: 每天 10,000 次 API 呼叫，100 個用戶，5 個模型

| 操作 | 次數/天 | 月成本 |
|------|---------|--------|
| 原始事件寫入 | 10,000 | $3.24 |
| 每日用戶聚合更新 | 10,000 | $3.24 |
| 每日模型聚合更新 | 10,000 | $3.24 |
| 用戶總計更新 | 10,000 | $3.24 |
| 儀表板讀取 (100 用戶 × 10 次/天) | 1,000 | $0.01 |
| **總計 (不含儲存)** | | **~$13/月** |

**最佳化後** (不保留原始事件):

| 操作 | 次數/天 | 月成本 |
|------|---------|--------|
| 每日用戶聚合更新 | 10,000 | $3.24 |
| 每日模型聚合更新 | 10,000 | $3.24 |
| 用戶總計更新 | 10,000 | $3.24 |
| 儀表板讀取 | 1,000 | $0.01 |
| **總計** | | **~$10/月** |

---

## 4. 資料模型設計

### 4.1 Collection 結構

```
firestore/
├── raw_events/                      # 原始事件 (可選)
│   └── {eventId}                    # CloudEvent ID
│
├── daily_user_usage/                # 每日用戶聚合
│   └── {date}_{userId}              # e.g., "2025-12-09_user-uuid-123"
│
├── daily_model_usage/               # 每日模型聚合
│   └── {date}_{model}               # e.g., "2025-12-09_claude-sonnet-4-5"
│
├── user_summary/                    # 用戶總計
│   └── {userId}                     # e.g., "user-uuid-123"
│
└── system_stats/                    # 系統層級統計
    └── {date}                       # e.g., "2025-12-09"
```

### 4.2 Document Schema

#### 4.2.1 原始事件 (raw_events) - 可選

```json
{
  "_id": "4c71578c899ae6249e5b70d07900fc93",
  "userId": "user-uuid-12345",
  "model": "claude-sonnet-4-5-20250929",
  "messageId": "msg_016pGU1jGmczbq7p4JTfAqmn",
  "inputTokens": 30,
  "outputTokens": 148,
  "cacheCreationTokens": 0,
  "cacheReadTokens": 0,
  "totalTokens": 178,
  "latencyMs": 7257,
  "stream": true,
  "stopReason": "end_turn",
  "status": "success",
  "keyAlias": "primary",
  "traceId": "4c71578c899ae6249e5b70d07900fc93",
  "anthropicRequestId": "req_018EeWyXxfu5pfWkrYcMdjWG",
  "timestamp": "2025-12-09T10:30:00.000Z",
  "createdAt": "2025-12-09T10:30:01.234Z"
}
```

#### 4.2.2 每日用戶聚合 (daily_user_usage)

```json
{
  "_id": "2025-12-09_user-uuid-12345",
  "date": "2025-12-09",
  "userId": "user-uuid-12345",
  "totalInputTokens": 15000,
  "totalOutputTokens": 8500,
  "totalCacheCreationTokens": 500,
  "totalCacheReadTokens": 3000,
  "totalTokens": 27000,
  "requestCount": 150,
  "successCount": 148,
  "errorCount": 2,
  "totalLatencyMs": 450000,
  "avgLatencyMs": 3000,
  "modelBreakdown": {
    "claude-sonnet-4-5-20250929": {
      "inputTokens": 10000,
      "outputTokens": 6000,
      "requestCount": 100
    },
    "claude-haiku-3-5-20250929": {
      "inputTokens": 5000,
      "outputTokens": 2500,
      "requestCount": 50
    }
  },
  "estimatedCostUsd": 0.285,
  "lastUpdatedAt": "2025-12-09T23:59:59.000Z"
}
```

#### 4.2.3 每日模型聚合 (daily_model_usage)

```json
{
  "_id": "2025-12-09_claude-sonnet-4-5-20250929",
  "date": "2025-12-09",
  "model": "claude-sonnet-4-5-20250929",
  "totalInputTokens": 500000,
  "totalOutputTokens": 280000,
  "totalCacheCreationTokens": 10000,
  "totalCacheReadTokens": 50000,
  "totalTokens": 840000,
  "requestCount": 3500,
  "successCount": 3480,
  "errorCount": 20,
  "uniqueUsers": 85,
  "avgLatencyMs": 2800,
  "estimatedCostUsd": 5.70,
  "lastUpdatedAt": "2025-12-09T23:59:59.000Z"
}
```

#### 4.2.4 用戶總計 (user_summary)

```json
{
  "_id": "user-uuid-12345",
  "userId": "user-uuid-12345",
  "totalInputTokens": 1500000,
  "totalOutputTokens": 850000,
  "totalTokens": 2350000,
  "totalRequestCount": 15000,
  "totalEstimatedCostUsd": 28.50,
  "firstSeenAt": "2025-11-01T08:30:00.000Z",
  "lastActiveAt": "2025-12-09T10:30:00.000Z",
  "lastUpdatedAt": "2025-12-09T10:30:01.234Z"
}
```

#### 4.2.5 系統統計 (system_stats)

```json
{
  "_id": "2025-12-09",
  "date": "2025-12-09",
  "totalInputTokens": 2000000,
  "totalOutputTokens": 1200000,
  "totalTokens": 3200000,
  "totalRequestCount": 12000,
  "uniqueUsers": 95,
  "totalEstimatedCostUsd": 45.60,
  "topModels": [
    {"model": "claude-sonnet-4-5-20250929", "requestCount": 8000},
    {"model": "claude-haiku-3-5-20250929", "requestCount": 4000}
  ],
  "topUsers": [
    {"userId": "user-uuid-123", "requestCount": 500},
    {"userId": "user-uuid-456", "requestCount": 450}
  ],
  "lastUpdatedAt": "2025-12-09T23:59:59.000Z"
}
```

### 4.3 成本計算公式

根據 Anthropic 定價 (2025-12):

```java
public record ModelPricing(
    String model,
    BigDecimal inputPricePerMillion,
    BigDecimal outputPricePerMillion,
    BigDecimal cacheReadPricePerMillion,
    BigDecimal cacheWritePricePerMillion
) {}

// Claude Sonnet 4: $3/$15 per 1M tokens
// Claude Opus 4: $15/$75 per 1M tokens
// Claude Haiku 3.5: $0.80/$4 per 1M tokens

public BigDecimal calculateCost(UsageEvent event) {
    ModelPricing pricing = getPricing(event.model());

    BigDecimal inputCost = BigDecimal.valueOf(event.inputTokens() - event.cacheReadTokens())
        .multiply(pricing.inputPricePerMillion())
        .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);

    BigDecimal cacheReadCost = BigDecimal.valueOf(event.cacheReadTokens())
        .multiply(pricing.cacheReadPricePerMillion())
        .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);

    BigDecimal cacheWriteCost = BigDecimal.valueOf(event.cacheCreationTokens())
        .multiply(pricing.cacheWritePricePerMillion())
        .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);

    BigDecimal outputCost = BigDecimal.valueOf(event.outputTokens())
        .multiply(pricing.outputPricePerMillion())
        .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);

    return inputCost.add(cacheReadCost).add(cacheWriteCost).add(outputCost);
}
```

---

## 5. 功能需求

### 5.1 核心功能

#### 5.1.1 事件接收與處理

**目的**: 從 GCP Pub/Sub 接收 CloudEvents 格式的用量事件

**Pub/Sub 配置**:
- Topic: `llm-gateway-usage`
- Subscription: `llm-usage-analytics-sub`
- 訊息格式: CloudEvents v1.0

**處理流程**:
1. 接收 CloudEvents 訊息
2. 解析並驗證事件資料
3. 計算預估成本
4. 批量更新聚合文件
5. 可選: 儲存原始事件

#### 5.1.2 聚合更新

**目的**: 即時更新預聚合統計資料

**更新策略**:
- 使用 MongoDB 的 `$inc` 原子操作符
- 批量更新減少寫入次數 (`bulkWrite`)
- 支援 upsert (不存在則建立)

```java
// MongoDB 原子更新範例
MongoCollection<Document> collection = database.getCollection("daily_user_usage");

// 使用 $inc 操作符進行原子累加
Bson filter = Filters.eq("_id", date + "_" + userId);
Bson update = Updates.combine(
    Updates.inc("totalInputTokens", event.inputTokens()),
    Updates.inc("totalOutputTokens", event.outputTokens()),
    Updates.inc("requestCount", 1),
    Updates.setOnInsert("date", date),
    Updates.setOnInsert("userId", userId),
    Updates.set("lastUpdatedAt", Instant.now())
);

collection.updateOne(filter, update, new UpdateOptions().upsert(true));
```

```java
// 批量寫入範例
List<WriteModel<Document>> operations = new ArrayList<>();

for (UsageEvent event : events) {
    operations.add(new UpdateOneModel<>(
        Filters.eq("_id", event.date() + "_" + event.userId()),
        Updates.combine(
            Updates.inc("totalInputTokens", event.inputTokens()),
            Updates.inc("totalOutputTokens", event.outputTokens()),
            Updates.inc("requestCount", 1),
            Updates.set("lastUpdatedAt", Instant.now())
        ),
        new UpdateOptions().upsert(true)
    ));
}

collection.bulkWrite(operations, new BulkWriteOptions().ordered(false));
```

#### 5.1.3 資料清理

**目的**: 清理過期的原始事件以節省儲存成本

**策略**:
- **不在此服務實作排程**
- 聚合資料永久保留
- 原始事件清理由外部處理：
  - 選項 1: GCP Cloud Scheduler + Cloud Functions
  - 選項 2: 獨立的資料維護服務
  - 選項 3: Firestore TTL 政策 (如支援)

### 5.2 分析網頁功能

#### 5.2.1 用戶用量儀表板

**功能**:
- 顯示用戶每日 Token 用量趨勢圖
- 顯示用戶模型使用分布
- 顯示預估成本
- 支援日期範圍篩選

**UI 元件**:
```
┌──────────────────────────────────────────────────────────────┐
│  用戶用量分析                              日期: [過去7天 ▼]  │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌────────────────────┐  ┌────────────────────┐              │
│  │   總 Token 數       │  │   預估成本          │              │
│  │   1,234,567        │  │   $18.50           │              │
│  └────────────────────┘  └────────────────────┘              │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                  每日用量趨勢圖                         │   │
│  │   ▲                                                   │   │
│  │   │    ┌─┐                                           │   │
│  │   │ ┌─┐│ │┌─┐                         ┌─┐            │   │
│  │   │ │ ││ ││ │    ┌─┐       ┌─┐  ┌─┐  │ │            │   │
│  │   └─┴─┴┴─┴┴─┴────┴─┴───────┴─┴──┴─┴──┴─┴───────────▶│   │
│  │     12/3 12/4 12/5 12/6 12/7 12/8 12/9              │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  ┌──────────────────────────┐                               │
│  │   模型使用分布 (圓餅圖)    │                               │
│  │   ● Sonnet 4: 65%        │                               │
│  │   ● Haiku 3.5: 30%       │                               │
│  │   ● Opus 4: 5%           │                               │
│  └──────────────────────────┘                               │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

#### 5.2.2 模型用量儀表板

**功能**:
- 顯示各模型的每日用量趨勢
- 顯示模型使用者數量
- 顯示模型成本分布
- 比較不同模型的使用情況

**UI 元件**:
```
┌──────────────────────────────────────────────────────────────┐
│  模型用量分析                              日期: [過去7天 ▼]  │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ 模型         │ 請求數   │ Token 數    │ 成本      │ 用戶 │   │
│  ├──────────────────────────────────────────────────────┤   │
│  │ Sonnet 4     │ 8,500   │ 2,500,000  │ $37.50   │ 85  │   │
│  │ Haiku 3.5    │ 4,200   │ 800,000    │ $4.00    │ 60  │   │
│  │ Opus 4       │ 300     │ 150,000    │ $6.75    │ 12  │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                  各模型每日趨勢圖                       │   │
│  │   ▲                                                   │   │
│  │   │    ━━━ Sonnet 4                                  │   │
│  │   │    ─── Haiku 3.5                                 │   │
│  │   │    ··· Opus 4                                    │   │
│  │   └───────────────────────────────────────────────▶ │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

#### 5.2.3 系統總覽儀表板

**功能**:
- 顯示系統整體用量統計
- 顯示活躍用戶數量趨勢
- 顯示 Top 10 用戶排行
- 顯示成本趨勢

### 5.3 API 端點

#### 5.3.1 用戶用量 API

```http
GET /api/v1/usage/users/{userId}/daily
Query Parameters:
  - startDate: YYYY-MM-DD (required)
  - endDate: YYYY-MM-DD (required)

Response:
{
  "userId": "user-uuid-123",
  "period": {
    "start": "2025-12-01",
    "end": "2025-12-09"
  },
  "summary": {
    "totalInputTokens": 150000,
    "totalOutputTokens": 85000,
    "totalTokens": 235000,
    "totalRequests": 1500,
    "estimatedCostUsd": 2.85
  },
  "daily": [
    {
      "date": "2025-12-01",
      "inputTokens": 15000,
      "outputTokens": 8500,
      "requests": 150,
      "costUsd": 0.28
    },
    // ...
  ]
}
```

#### 5.3.2 模型用量 API

```http
GET /api/v1/usage/models/{model}/daily
Query Parameters:
  - startDate: YYYY-MM-DD (required)
  - endDate: YYYY-MM-DD (required)

Response:
{
  "model": "claude-sonnet-4-5-20250929",
  "period": {
    "start": "2025-12-01",
    "end": "2025-12-09"
  },
  "summary": {
    "totalInputTokens": 5000000,
    "totalOutputTokens": 2800000,
    "totalRequests": 35000,
    "uniqueUsers": 95,
    "estimatedCostUsd": 57.00
  },
  "daily": [
    // ...
  ]
}
```

#### 5.3.3 系統統計 API

```http
GET /api/v1/usage/system/daily
Query Parameters:
  - startDate: YYYY-MM-DD (required)
  - endDate: YYYY-MM-DD (required)

Response:
{
  "period": {
    "start": "2025-12-01",
    "end": "2025-12-09"
  },
  "summary": {
    "totalTokens": 32000000,
    "totalRequests": 120000,
    "uniqueUsers": 150,
    "estimatedCostUsd": 456.00
  },
  "daily": [
    // ...
  ],
  "topUsers": [
    // ...
  ],
  "topModels": [
    // ...
  ]
}
```

---

## 6. 技術架構

### 6.1 專案結構

```
ledger/
├── src/main/java/io/github/samzhu/ledger/
│   ├── LedgerApplication.java                   # Spring Boot 應用程式
│   ├── config/
│   │   ├── MongoConfig.java                     # MongoDB 配置 (啟用 Auditing)
│   │   ├── StreamConfig.java                    # Spring Cloud Stream 配置
│   │   ├── WebConfig.java                       # Web MVC 配置
│   │   ├── ObservabilityConfig.java             # 可觀測性配置
│   │   └── NativeImageHints.java                # GraalVM Native 配置
│   ├── function/
│   │   └── UsageEventFunction.java              # Spring Cloud Stream 消費者 (Function 風格)
│   ├── service/
│   │   ├── UsageAggregationService.java         # 聚合計算服務
│   │   ├── EventBufferService.java              # 事件緩衝服務 (含優雅關閉)
│   │   ├── UsageQueryService.java               # 查詢服務
│   │   └── CostCalculationService.java          # 成本計算服務
│   ├── repository/
│   │   ├── DailyUserUsageRepository.java        # 用戶聚合存取 (Spring Data MongoDB)
│   │   ├── DailyModelUsageRepository.java       # 模型聚合存取
│   │   ├── UserSummaryRepository.java           # 用戶總計存取
│   │   └── SystemStatsRepository.java           # 系統統計存取
│   ├── document/                                # MongoDB Document (Entity)
│   │   ├── DailyUserUsage.java                  # 用戶聚合 Document
│   │   ├── DailyModelUsage.java                 # 模型聚合 Document
│   │   ├── UserSummary.java                     # 用戶總計 Document
│   │   └── SystemStats.java                     # 系統統計 Document
│   ├── dto/
│   │   ├── UsageEvent.java                      # 用量事件 DTO
│   │   ├── UsageEventData.java                  # CloudEvents data payload
│   │   └── ModelPricing.java                    # 模型定價配置
│   └── controller/
│       ├── UsageApiController.java              # REST API 控制器
│       └── DashboardController.java             # 網頁控制器
│
├── src/main/resources/
│   ├── application.yaml                         # 基礎配置
│   ├── application-local.yaml                   # 本地開發配置 (RabbitMQ + 本地 MongoDB)
│   ├── application-gcp.yaml                     # GCP 配置 (Pub/Sub + Firestore)
│   ├── static/                                  # 靜態資源
│   │   ├── css/
│   │   └── js/
│   └── templates/                               # Thymeleaf 模板
│       ├── dashboard/
│       │   ├── user.html
│       │   ├── model.html
│       │   └── system.html
│       └── layout/
│           └── main.html
│
├── src/test/java/
├── compose.yaml                                 # Docker Compose (本地開發)
└── build.gradle
```

### 6.2 核心元件

#### 6.2.1 Spring Cloud Stream 消費者 (Function 風格)

**Spring Boot 3.x / Spring Cloud 2025.x 推薦寫法**：使用 `java.util.function.Consumer` 或 `Function`。

```java
package io.github.samzhu.ledger.function;

import java.util.function.Consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

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

    /**
     * 消費 CloudEvents 格式的用量事件
     *
     * Spring Cloud Stream 會自動將此 Bean 綁定到 usageEventConsumer-in-0
     */
    @Bean
    public Consumer<Message<CloudEvent>> usageEventConsumer() {
        return message -> {
            try {
                CloudEvent cloudEvent = message.getPayload();
                UsageEvent event = parseUsageEvent(cloudEvent);

                // 加入緩衝區，批量處理
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

#### 6.2.2 事件緩衝服務 (含優雅關閉)

**重點**: 實作 `SmartLifecycle` 確保服務關閉時將累積的事件寫入資料庫。

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

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

    public EventBufferService(
            UsageAggregationService aggregationService,
            @Value("${usage-analytics.batch.size:100}") int batchSize,
            @Value("${usage-analytics.batch.interval-ms:5000}") long flushIntervalMs) {
        this.aggregationService = aggregationService;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
    }

    /**
     * 加入事件到緩衝區
     */
    public void addEvent(UsageEvent event) {
        eventBuffer.add(event);

        // 達到批量大小時立即處理
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
            log.info("Flushed {} events to database", batch.size());
        } catch (Exception e) {
            log.error("Failed to flush events, re-adding to buffer: {}", e.getMessage(), e);
            // 失敗時將事件放回緩衝區
            eventBuffer.addAll(0, batch);
        }
    }

    // ===== SmartLifecycle 實作 =====

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            // 啟動定時 flush 任務
            scheduler.scheduleAtFixedRate(
                this::flushBuffer,
                flushIntervalMs,
                flushIntervalMs,
                TimeUnit.MILLISECONDS
            );
            log.info("EventBufferService started: batchSize={}, flushIntervalMs={}",
                batchSize, flushIntervalMs);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("EventBufferService stopping, flushing remaining {} events...",
                eventBuffer.size());

            // 關閉排程器
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
        // 在 Spring Cloud Stream bindings 之後關閉 (確保不再收到新事件)
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

#### 6.2.3 聚合服務 (Spring Data MongoDB + MongoTemplate)

使用 `MongoTemplate` 進行原子更新操作：

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

import io.github.samzhu.ledger.document.DailyUserUsage;
import io.github.samzhu.ledger.document.DailyModelUsage;
import io.github.samzhu.ledger.document.UserSummary;
import io.github.samzhu.ledger.document.SystemStats;
import io.github.samzhu.ledger.dto.UsageEvent;

/**
 * 聚合服務 - 使用 Spring Data MongoDB MongoTemplate
 */
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
     * 批量處理事件
     */
    public void processBatch(List<UsageEvent> events) {
        if (events.isEmpty()) return;

        long startTime = System.currentTimeMillis();

        // 更新每日用戶聚合
        updateDailyUserUsage(events);

        // 更新每日模型聚合
        updateDailyModelUsage(events);

        // 更新用戶總計
        updateUserSummary(events);

        // 更新系統統計
        updateSystemStats(events);

        log.debug("Processed {} events in {}ms",
            events.size(), System.currentTimeMillis() - startTime);
    }

    private void updateDailyUserUsage(List<UsageEvent> events) {
        BulkOperations bulkOps = mongoTemplate.bulkOps(
            BulkOperations.BulkMode.UNORDERED, DailyUserUsage.class);

        // 按 用戶+日期 分組
        Map<String, List<UsageEvent>> grouped = events.stream()
            .collect(Collectors.groupingBy(e -> e.date() + "_" + e.userId()));

        grouped.forEach((docId, userEvents) -> {
            UsageEvent first = userEvents.get(0);
            int totalInput = userEvents.stream().mapToInt(UsageEvent::inputTokens).sum();
            int totalOutput = userEvents.stream().mapToInt(UsageEvent::outputTokens).sum();
            int totalCacheCreation = userEvents.stream().mapToInt(UsageEvent::cacheCreationTokens).sum();
            int totalCacheRead = userEvents.stream().mapToInt(UsageEvent::cacheReadTokens).sum();
            int totalTokens = userEvents.stream().mapToInt(UsageEvent::totalTokens).sum();
            long totalLatency = userEvents.stream().mapToLong(UsageEvent::latencyMs).sum();
            int successCount = (int) userEvents.stream().filter(e -> "success".equals(e.status())).count();
            int errorCount = userEvents.size() - successCount;

            BigDecimal totalCost = userEvents.stream()
                .map(costService::calculateCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            Query query = Query.query(Criteria.where("_id").is(docId));
            Update update = new Update()
                .setOnInsert("date", first.date().toString())
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
        BulkOperations bulkOps = mongoTemplate.bulkOps(
            BulkOperations.BulkMode.UNORDERED, DailyModelUsage.class);

        // 按 模型+日期 分組
        Map<String, List<UsageEvent>> grouped = events.stream()
            .collect(Collectors.groupingBy(e -> e.date() + "_" + e.model()));

        grouped.forEach((docId, modelEvents) -> {
            UsageEvent first = modelEvents.get(0);
            int totalInput = modelEvents.stream().mapToInt(UsageEvent::inputTokens).sum();
            int totalOutput = modelEvents.stream().mapToInt(UsageEvent::outputTokens).sum();

            BigDecimal totalCost = modelEvents.stream()
                .map(costService::calculateCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            Query query = Query.query(Criteria.where("_id").is(docId));
            Update update = new Update()
                .setOnInsert("date", first.date().toString())
                .setOnInsert("model", first.model())
                .inc("totalInputTokens", totalInput)
                .inc("totalOutputTokens", totalOutput)
                .inc("requestCount", modelEvents.size())
                .inc("estimatedCostUsd", totalCost.doubleValue())
                .set("lastUpdatedAt", Instant.now());

            bulkOps.upsert(query, update);
        });

        bulkOps.execute();
    }

    private void updateUserSummary(List<UsageEvent> events) {
        BulkOperations bulkOps = mongoTemplate.bulkOps(
            BulkOperations.BulkMode.UNORDERED, UserSummary.class);

        // 按用戶分組
        Map<String, List<UsageEvent>> grouped = events.stream()
            .collect(Collectors.groupingBy(UsageEvent::userId));

        grouped.forEach((userId, userEvents) -> {
            int totalTokens = userEvents.stream().mapToInt(UsageEvent::totalTokens).sum();
            BigDecimal totalCost = userEvents.stream()
                .map(costService::calculateCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            Query query = Query.query(Criteria.where("_id").is(userId));
            Update update = new Update()
                .setOnInsert("userId", userId)
                .setOnInsert("firstSeenAt", Instant.now())
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
        BulkOperations bulkOps = mongoTemplate.bulkOps(
            BulkOperations.BulkMode.UNORDERED, SystemStats.class);

        // 按日期分組
        Map<LocalDate, List<UsageEvent>> grouped = events.stream()
            .collect(Collectors.groupingBy(UsageEvent::date));

        grouped.forEach((date, dateEvents) -> {
            int totalTokens = dateEvents.stream().mapToInt(UsageEvent::totalTokens).sum();
            BigDecimal totalCost = dateEvents.stream()
                .map(costService::calculateCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            Query query = Query.query(Criteria.where("_id").is(date.toString()));
            Update update = new Update()
                .setOnInsert("date", date.toString())
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

#### 6.2.4 查詢服務 (Spring Data MongoDB Repository)

使用 Spring Data MongoDB Repository 進行查詢：

**Repository 定義**:
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

**查詢服務**:
```java
package io.github.samzhu.ledger.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import io.github.samzhu.ledger.document.DailyUserUsage;
import io.github.samzhu.ledger.document.DailyModelUsage;
import io.github.samzhu.ledger.document.UserSummary;
import io.github.samzhu.ledger.repository.DailyUserUsageRepository;
import io.github.samzhu.ledger.repository.DailyModelUsageRepository;
import io.github.samzhu.ledger.repository.UserSummaryRepository;

/**
 * 查詢服務 - 使用 Spring Data MongoDB Repository
 */
@Service
public class UsageQueryService {

    private final DailyUserUsageRepository dailyUserUsageRepository;
    private final DailyModelUsageRepository dailyModelUsageRepository;
    private final UserSummaryRepository userSummaryRepository;

    public UsageQueryService(
            DailyUserUsageRepository dailyUserUsageRepository,
            DailyModelUsageRepository dailyModelUsageRepository,
            UserSummaryRepository userSummaryRepository) {
        this.dailyUserUsageRepository = dailyUserUsageRepository;
        this.dailyModelUsageRepository = dailyModelUsageRepository;
        this.userSummaryRepository = userSummaryRepository;
    }

    /**
     * 查詢用戶每日用量
     * 使用 findByIdIn 一次查詢多個文件，最佳化讀取成本
     */
    public List<DailyUserUsage> getUserDailyUsage(
            String userId,
            LocalDate startDate,
            LocalDate endDate) {

        // 建立要查詢的文件 ID 列表
        List<String> docIds = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            docIds.add(date.toString() + "_" + userId);
        }

        // 使用 $in 查詢多個文件 (一次查詢)
        return dailyUserUsageRepository.findByIdIn(docIds);
    }

    /**
     * 查詢模型每日用量
     */
    public List<DailyModelUsage> getModelDailyUsage(
            String model,
            LocalDate startDate,
            LocalDate endDate) {

        List<String> docIds = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            docIds.add(date.toString() + "_" + model);
        }

        return dailyModelUsageRepository.findByIdIn(docIds);
    }

    /**
     * 查詢用戶總計
     */
    public Optional<UserSummary> getUserSummary(String userId) {
        return userSummaryRepository.findById(userId);
    }

    /**
     * 查詢所有用戶總計 (分頁，按 totalTokens 排序)
     */
    public List<UserSummary> getTopUsers(int page, int size) {
        return userSummaryRepository.findAllByOrderByTotalTokensDesc(
            PageRequest.of(page, size));
    }
}
```

### 6.3 內嵌網頁技術選型

| 元件 | 技術選擇 | 說明 |
|------|---------|------|
| **模板引擎** | Thymeleaf | Spring Boot 原生支援，SSR |
| **CSS 框架** | Tailwind CSS | 輕量、現代化 UI |
| **圖表庫** | Chart.js | 輕量、易用的圖表 |
| **互動性** | htmx | 輕量 AJAX，避免 SPA 複雜度 |

### 6.4 依賴管理

**build.gradle**:
```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.5.8'
    id 'io.spring.dependency-management' version '1.1.7'
    id 'org.graalvm.buildtools.native' version '0.10.6'
    id 'org.cyclonedx.bom' version '2.3.0'
}

group = 'io.github.samzhu'
version = '0.0.1-SNAPSHOT'
description = 'LLM Usage Ledger - Token usage tracking and analytics service'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

configurations {
    compileOnly {
        extendsFrom annotationProcessor
    }
}

repositories {
    mavenCentral()
}

ext {
    set('springCloudVersion', "2025.0.0")
    set('springCloudGcpVersion', "7.4.1")
    set('cloudeventsVersion', "4.0.1")
}

dependencies {
    // Web & Template
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-thymeleaf'
    implementation 'org.springframework.boot:spring-boot-starter-validation'

    // MongoDB (存取 Firestore MongoDB 相容 API)
    implementation 'org.springframework.boot:spring-boot-starter-data-mongodb'

    // Spring Cloud Stream (訊息消費抽象層)
    implementation 'org.springframework.cloud:spring-cloud-stream'

    // GCP Pub/Sub Binder
    implementation 'com.google.cloud:spring-cloud-gcp-starter-pubsub'

    // CloudEvents
    implementation "io.cloudevents:cloudevents-spring:${cloudeventsVersion}"
    implementation "io.cloudevents:cloudevents-json-jackson:${cloudeventsVersion}"

    // 可觀測性 (Spring Boot 3.x 風格 - Brave)
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'io.micrometer:micrometer-tracing-bridge-brave'
    runtimeOnly 'io.micrometer:micrometer-registry-otlp'

    // 快取 (可選，用於減少重複查詢)
    implementation 'org.springframework.boot:spring-boot-starter-cache'

    // 開發工具
    developmentOnly 'org.springframework.boot:spring-boot-devtools'
    developmentOnly 'org.springframework.boot:spring-boot-docker-compose'
    annotationProcessor 'org.springframework.boot:spring-boot-configuration-processor'

    // 測試
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.boot:spring-boot-testcontainers'
    testImplementation 'org.springframework.cloud:spring-cloud-stream-test-binder'
    testImplementation 'org.testcontainers:gcloud'
    testImplementation 'org.testcontainers:junit-jupiter'
    testImplementation 'org.testcontainers:mongodb'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

dependencyManagement {
    imports {
        mavenBom "org.springframework.cloud:spring-cloud-dependencies:${springCloudVersion}"
        mavenBom "com.google.cloud:spring-cloud-gcp-dependencies:${springCloudGcpVersion}"
    }
}

tasks.named('test') {
    useJUnitPlatform()
}
```

> **技術選擇說明**:
> - **Spring Boot 3.5.8**: 目前最新穩定版本
> - **Spring Data MongoDB**: 比原生 MongoDB Driver 更便捷，提供 Repository 抽象
> - **Micrometer Brave**: Spring Boot 3.x 推薦的 Tracing Bridge
> - **不使用 Lombok**: 現代 Java 的 Record 已足夠簡潔
> - **不做排程**: 資料清理由外部服務處理

### 6.5 配置

**application.yaml** (基礎配置):
```yaml
spring:
  application:
    name: ledger
  profiles:
    default: local,dev
  # 啟用 Virtual Threads (Java 21+)
  threads:
    virtual:
      enabled: true
  cloud:
    refresh:
      enabled: false  # AOT/Native Image 不支援 RefreshScope
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
  thymeleaf:
    cache: false  # 開發時關閉快取

# 應用程式配置
ledger:
  batch:
    size: 100           # 累積 100 筆後批量寫入
    interval-ms: 5000   # 或每 5 秒寫入一次
  pricing:
    claude-sonnet-4:
      input-per-million: 3.00
      output-per-million: 15.00
      cache-read-per-million: 0.30
      cache-write-per-million: 3.75
    claude-opus-4:
      input-per-million: 15.00
      output-per-million: 75.00
      cache-read-per-million: 1.50
      cache-write-per-million: 18.75
    claude-haiku-3-5:
      input-per-million: 0.80
      output-per-million: 4.00
      cache-read-per-million: 0.08
      cache-write-per-million: 1.00

# 優雅關閉配置
server:
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s

# 可觀測性 (Spring Boot 3.x)
management:
  tracing:
    sampling:
      probability: 1.0
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

logging:
  level:
    root: INFO
    io.github.samzhu.ledger: DEBUG
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId:-},%X{spanId:-}] %-5level %logger{36} - %msg%n"
```

**application-local.yaml** (本地開發):
```yaml
spring:
  cloud:
    gcp:
      core:
        enabled: false
      pubsub:
        enabled: false
    stream:
      default-binder: rabbit
      bindings:
        usageEventConsumer-in-0:
          binder: rabbit
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME:guest}
    password: ${RABBITMQ_PASSWORD:guest}
  data:
    mongodb:
      uri: mongodb://localhost:27017/ledger
```

**application-gcp.yaml** (GCP 生產):
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
      # GCP Firestore MongoDB 相容 API 連接字串
      # 需要設定 GOOGLE_APPLICATION_CREDENTIALS 環境變數
      uri: mongodb://${GCP_PROJECT_ID}:${FIRESTORE_DATABASE_ID:(default)}@firestore.googleapis.com:443/?ssl=true&authMechanism=MONGODB-OIDC&authMechanismProperties=ENVIRONMENT:GCP
```

> **注意**: GCP Firestore MongoDB 相容 API 需要啟用 Firestore Native Mode，並確保服務帳號有適當權限。

**Spring Data MongoDB 自動配置**:

使用 `spring-boot-starter-data-mongodb` 後，無需手動配置 `MongoClient`，Spring Boot 會根據 `spring.data.mongodb.uri` 自動配置。

如需自訂配置，可建立 `MongoConfig`:

```java
package io.github.samzhu.ledger.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@Configuration
@EnableMongoRepositories(basePackages = "io.github.samzhu.ledger.repository")
@EnableMongoAuditing  // 啟用 @CreatedDate, @LastModifiedDate
public class MongoConfig {
    // Spring Boot 自動配置 MongoClient
    // 若需客製化可覆寫 MongoClientSettings
}
```

---

## 7. 處理流程

### 7.1 事件接收與聚合流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           事件處理流程                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   Pub/Sub / RabbitMQ         EventBufferService           MongoDB/Firestore│
│                                                                             │
│   ┌─────────┐                ┌─────────────┐                               │
│   │ Event 1 │───────────────▶│             │                               │
│   └─────────┘                │   Event     │                               │
│   ┌─────────┐   Spring       │   Buffer    │    達到 100 筆               │
│   │ Event 2 │───Cloud ──────▶│             │────────或────────▶ bulkWrite │
│   └─────────┘   Stream       │  (最多100)  │    每 5 秒                   │
│       ...                    │             │                               │
│   ┌─────────┐                │             │  ┌──────────────────┐        │
│   │ Event N │───────────────▶│             │  │ SIGTERM 信號時   │        │
│   └─────────┘                └──────┬──────┘  │ 強制 flush 所有  │        │
│                                     │         │ 累積事件         │        │
│                                     │         └──────────────────┘        │
│                                     ▼                                      │
│                         ┌─────────────────────┐                            │
│                         │  UsageAggregation   │                            │
│                         │  Service            │                            │
│                         │                     │                            │
│                         │  Aggregate by:      │                            │
│                         │  - User + Date      │                            │
│                         │  - Model + Date     │                            │
│                         │  - User (summary)   │                            │
│                         │  - System (daily)   │                            │
│                         └─────────────────────┘                            │
│                                    │                                        │
│                                    ▼                                        │
│                         ┌─────────────────────┐     ┌──────────────────┐  │
│                         │   MongoDB bulkWrite │────▶│  daily_user_usage │  │
│                         │   (UpdateOneModel   │     │  daily_model_usage│  │
│                         │    + $inc 原子操作)  │     │  user_summary     │  │
│                         └─────────────────────┘     │  system_stats     │  │
│                                                     └──────────────────┘  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 7.2 查詢流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           查詢流程 (成本最佳化)                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   Browser                  UsageQueryService             MongoDB/Firestore │
│                                                                             │
│   ┌────────────────┐                                                       │
│   │ GET /dashboard │                                                       │
│   │ ?userId=xxx    │                                                       │
│   │ &start=12-01   │                                                       │
│   │ &end=12-09     │                                                       │
│   └───────┬────────┘                                                       │
│           │                                                                 │
│           ▼                                                                 │
│   ┌───────────────────────────┐                                            │
│   │ 建立文件 ID 列表           │                                            │
│   │ - 2025-12-01_xxx          │                                            │
│   │ - 2025-12-02_xxx          │                                            │
│   │ - ...                     │                                            │
│   │ - 2025-12-09_xxx          │                                            │
│   └───────────┬───────────────┘                                            │
│               │                                                             │
│               ▼                                                             │
│   ┌───────────────────────────┐     ┌────────────────────┐                 │
│   │ MongoDB find() 查詢       │     │  daily_user_usage  │                 │
│   │ Filters.in("_id", ids)    │────▶│  使用 $in 一次查詢  │                 │
│   │ (單次查詢多個文件)         │     │  (比逐筆查詢更有效) │                 │
│   └───────────┬───────────────┘     └────────────────────┘                 │
│               │                                                             │
│               ▼                                                             │
│   ┌───────────────────────────┐                                            │
│   │ 組合結果，渲染 Thymeleaf   │                                            │
│   └───────────────────────────┘                                            │
│               │                                                             │
│               ▼                                                             │
│   ┌────────────────┐                                                       │
│   │ HTML + Chart   │                                                       │
│   └────────────────┘                                                       │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 8. 非功能性需求

### 8.1 效能

| 指標 | 目標 |
|------|------|
| 事件處理延遲 | < 10 秒 (從 Pub/Sub 到 Firestore) |
| 批量寫入延遲 | < 1 秒 |
| 儀表板載入時間 | < 2 秒 |
| API 回應時間 | < 500ms |

### 8.2 可用性

| 指標 | 目標 |
|------|------|
| 服務可用性 | 99.9% |
| 資料持久性 | 99.999% (Firestore SLA) |
| 事件處理保證 | At-least-once |

### 8.3 擴展性

| 場景 | 支援 |
|------|------|
| 每日事件量 | 100,000+ |
| 並發用戶 | 100+ |
| 歷史資料查詢 | 90 天 |

### 8.4 成本目標

| 項目 | 每月預算 |
|------|---------|
| Firestore | < $50 |
| Cloud Run | < $20 |
| Pub/Sub | < $5 |

---

## 9. 部署

### 9.1 GCP Cloud Run

```yaml
apiVersion: serving.knative.dev/v1
kind: Service
metadata:
  name: llm-usage-analytics
spec:
  template:
    metadata:
      annotations:
        autoscaling.knative.dev/minScale: "1"
        autoscaling.knative.dev/maxScale: "3"
    spec:
      containers:
        - image: llm-usage-analytics:latest
          ports:
            - containerPort: 8080
          resources:
            limits:
              memory: "512Mi"
              cpu: "1"
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "gcp"
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
```

### 9.2 Firestore 索引

由於使用文件 ID 直接存取，不需要複合索引。

但如果未來需要範圍查詢，可建立：

```
// firestore.indexes.json
{
  "indexes": [
    {
      "collectionGroup": "daily_user_usage",
      "queryScope": "COLLECTION",
      "fields": [
        { "fieldPath": "userId", "order": "ASCENDING" },
        { "fieldPath": "date", "order": "DESCENDING" }
      ]
    }
  ]
}
```

---

## 10. 安全考量

### 10.1 存取控制

- 儀表板可考慮整合 OAuth2 認證
- API 端點可加上 API Key 保護
- Firestore 安全規則限制直接存取

### 10.2 資料隱私

- 不儲存請求/回應內容
- 只儲存用量元數據
- userId 可考慮加密或 hash

---

## 11. 未來擴展

### 11.1 短期規劃

- 用戶配額警告通知
- 匯出 CSV/Excel 報表
- 多租戶支援

### 11.2 長期規劃

- 預測性分析 (用量趨勢預測)
- 異常檢測 (用量異常告警)
- 成本最佳化建議
- 與 BigQuery 整合做進階分析

---

## 附錄

### 附錄 A: CloudEvents 用量事件格式 (來自 Gate)

```json
{
  "specversion": "1.0",
  "id": "4c71578c899ae6249e5b70d07900fc93",
  "type": "io.github.samzhu.gate.usage.v1",
  "source": "/gate/messages",
  "subject": "user-uuid-12345",
  "time": "2025-11-26T10:30:00.000Z",
  "datacontenttype": "application/json",
  "data": {
    "model": "claude-sonnet-4-5-20250929",
    "message_id": "msg_016pGU1jGmczbq7p4JTfAqmn",
    "input_tokens": 30,
    "output_tokens": 148,
    "cache_creation_tokens": 0,
    "cache_read_tokens": 0,
    "total_tokens": 178,
    "latency_ms": 7257,
    "stream": true,
    "stop_reason": "end_turn",
    "status": "success",
    "key_alias": "primary",
    "trace_id": "4c71578c899ae6249e5b70d07900fc93",
    "anthropic_request_id": "req_018EeWyXxfu5pfWkrYcMdjWG"
  }
}
```

### 附錄 B: Firestore vs BigQuery 比較

| 面向 | Firestore | BigQuery |
|------|-----------|----------|
| **即時性** | 毫秒級 | 秒級 |
| **查詢彈性** | 有限 (需索引) | 非常高 (SQL) |
| **成本模型** | 按操作數 | 按資料量 |
| **最佳場景** | OLTP / 儀表板 | OLAP / 深度分析 |

**結論**: 對於即時儀表板和簡單聚合查詢，Firestore 更適合。若未來需要深度分析，可定期同步到 BigQuery。

### 附錄 C: 優雅關閉流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           優雅關閉流程                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   1. 收到 SIGTERM 信號                                                      │
│      │                                                                      │
│      ▼                                                                      │
│   2. Spring Cloud Stream 停止接收新訊息                                     │
│      │   (binding phase 停止)                                               │
│      │                                                                      │
│      ▼                                                                      │
│   3. EventBufferService.stop() 被呼叫                                       │
│      │   (SmartLifecycle phase: MAX_VALUE - 100)                           │
│      │                                                                      │
│      ├─────▶ 3.1 停止定時 flush 任務                                        │
│      │                                                                      │
│      ├─────▶ 3.2 flushBuffer() 將所有累積事件寫入 MongoDB                   │
│      │                                                                      │
│      └─────▶ 3.3 記錄 "EventBufferService stopped"                         │
│                                                                             │
│   4. MongoDB Client 關閉                                                    │
│                                                                             │
│   5. 應用程式完全關閉                                                        │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 附錄 D: 相關文件連結

- [MongoDB Java Driver](https://www.mongodb.com/docs/drivers/java/sync/current/)
- [Spring Cloud Stream Reference](https://docs.spring.io/spring-cloud-stream/reference/)
- [Spring Cloud GCP Pub/Sub Stream Binder](https://googlecloudplatform.github.io/spring-cloud-gcp/7.4.1/reference/html/index.html#spring-cloud-stream)
- [GCP Firestore MongoDB Compatibility](https://cloud.google.com/firestore/docs/reference/rest)
- [Firestore Pricing](https://cloud.google.com/firestore/pricing)
- [CloudEvents Specification](https://cloudevents.io/)
- [Spring Boot Graceful Shutdown](https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html)
- [SmartLifecycle Interface](https://docs.spring.io/spring-framework/reference/core/beans/factory-nature.html#beans-factory-lifecycle-processor)

---

## 文件修訂歷史

| 版本 | 日期 | 作者 | 變更 |
|------|------|------|------|
| 1.0.0 | 2025-12-09 | AI 助理 | 初始版本建立 |
| 1.1.0 | 2025-12-09 | AI 助理 | 重大更新：使用 MongoDB Driver 存取 Firestore；透過 Spring Cloud Stream 消費 (Function 風格)；新增 EventBufferService 實作 SmartLifecycle 處理優雅關閉；移除排程功能 |
| 1.2.0 | 2025-12-09 | AI 助理 | **專案命名為 Ledger**；降級至 Spring Boot 3.5.8 (穩定版)；改用 Spring Data MongoDB (spring-boot-starter-data-mongodb)；使用 Micrometer Brave (Spring Boot 3.x 推薦)；更新專案結構 (document/dto 分離)；MongoTemplate BulkOperations 取代原生 MongoDB Driver |

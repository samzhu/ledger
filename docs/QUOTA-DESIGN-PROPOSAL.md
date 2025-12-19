# Ledger Quota Management System - Design Proposal

## Document Information
- **Version**: 2.2.0
- **Created**: 2025-12-18
- **Updated**: 2025-12-19
- **Author**: AI Assistant
- **Status**: Draft for Review

---

## 1. Executive Summary

### 1.1 Background

Ledger 專案目前已具備完整的 LLM 用量追蹤和成本計算功能，需要完成配額（Quota）管理功能，讓企業可以控制每位用戶的月度使用預算。

### 1.2 Core Design Principles

| 原則 | 說明 |
|------|------|
| **USD 為主** | 人類更容易理解金額，配額以美元設定 |
| **Token 同步記錄** | 不同模型價格差異大，Token 紀錄有助分析 |
| **月度週期** | 每月 1 號 00:00:00 UTC 自動重置 |
| **歷史保留** | 重置時保留當月使用記錄供查詢 |
| **額外額度** | 管理員可直接給予用戶額外配額 |
| **ID 自動生成** | 所有 Document ID 由 MongoDB 自動產生 |

### 1.3 Scope

**本期實作**：
- 月度配額管理（USD + Token 紀錄）
- 額外額度給予功能（簡化版，無審核流程）
- 配額狀態查詢 API
- 管理介面
- **閾值視覺化呈現**（圓形進度條 + 顏色指示）

**暫不實作**：
- Gate Pre-request 配額檢查（未來擴展）
- 即時阻斷功能
- 額外額度審核流程
- 閾值通知功能（Email/Webhook 通知）

---

## 2. Technology Stack

### 2.1 Spring Data MongoDB

本專案使用 **Spring Data MongoDB** 套件簡化資料庫操作。

**官方文件**：https://docs.spring.io/spring-data/mongodb/reference/

#### 核心特性

| 特性 | 說明 |
|------|------|
| **Repository 介面** | 繼承 `MongoRepository<T, ID>` 自動獲得 CRUD 方法 |
| **Derived Query Methods** | 根據方法命名自動產生查詢 |
| **@Query 註解** | 自訂 MongoDB JSON 查詢 |
| **@Update 註解** | 定義更新操作，搭配 @Query 使用 |

#### 常用查詢關鍵字

| 關鍵字 | 範例 | MongoDB 查詢 |
|--------|------|--------------|
| `And` | `findByFirstnameAndLastname` | `{ firstname: x, lastname: y }` |
| `Or` | `findByFirstnameOrLastname` | `{ $or: [{...}, {...}] }` |
| `GreaterThan` | `findByAgeGreaterThan(int age)` | `{ age: { $gt: age } }` |
| `LessThan` | `findByAgeLessThan(int age)` | `{ age: { $lt: age } }` |
| `Between` | `findByAgeBetween(int from, int to)` | `{ age: { $gt: from, $lt: to } }` |
| `OrderBy` | `findByUserIdOrderByPeriodYearDesc` | 排序結果 |
| `True` / `False` | `findByQuotaEnabledTrue()` | `{ quotaEnabled: true }` |

#### 更新操作策略：@Query + @Update

本專案使用 `@Query` + `@Update` 註解進行資料更新，而非傳統 ORM 的 find-modify-save 模式。

**選擇此方式的原因**：

| 考量 | @Query + @Update | Find + Save |
|------|------------------|-------------|
| **原子性** | ✅ DB 層執行，避免併發問題 | ❌ 需樂觀鎖 `@Version` |
| **效能** | ✅ 1 次 DB 操作 | ❌ 2 次 DB 操作 |
| **$inc 支援** | ✅ 原生支援原子增量 | ❌ 需手動計算，有 race condition |

**併發問題範例**（find-modify-save 模式）：

```
T1: 用戶 A 讀取 bonusCostUsd = 10
T2: 用戶 B 讀取 bonusCostUsd = 10
T3: 用戶 A 增加 5，儲存 → bonusCostUsd = 15
T4: 用戶 B 增加 3，儲存 → bonusCostUsd = 13 ❌ (應該是 18)
```

使用 `$inc` 原子操作可避免此問題。

**Firestore MongoDB Compatibility 支援狀態**：

| 操作符 | 支援 | 說明 |
|--------|------|------|
| `$set` | ✅ | 設定欄位值 |
| `$inc` | ✅ | 原子增量 |
| `$unset` | ✅ | 移除欄位 |
| `$push` | ✅ | 陣列新增 |
| `$pull` | ✅ | 陣列移除 |
| `$currentDate` | ✅ | 設定當前時間 |

> **官方文件**：https://firebase.google.com/docs/firestore/enterprise/supported-features-36

#### @Update 註解範例

```java
// 固定值增量
@Query("{ 'userId': ?0 }")
@Update("{ '$inc': { 'periodRequestCount': 1 } }")
long incrementRequestCountByUserId(String userId);

// 參數化增量 + 設定欄位
@Query("{ 'userId': ?0 }")
@Update("{ '$inc': { 'periodCostUsd': ?1 }, '$set': { 'lastActiveAt': ?2 } }")
long incrementCostByUserId(String userId, BigDecimal cost, Instant now);
```

---

## 3. Data Model

### 3.1 Design Principles

1. **ID 自動生成**：所有 Document 的 `_id` 由 MongoDB 自動產生 ObjectId
2. **避免自定義類型**：不使用自定義 Class、Record、Enum（如 `YearMonth`、`QuotaConfig`、`QuotaPeriod`）
3. **年月標識**：使用 `periodYear` (int) + `periodMonth` (int) 標識週期，便於查詢
4. **統一月度週期**：不使用 `QuotaPeriod` enum（移除 DAILY/WEEKLY 選項），統一以月為單位管理

#### 允許的欄位類型

| 類型 | 說明 | 範例 |
|------|------|------|
| `String` | 字串，用於 ID、名稱、格式化日期 | `"user-uuid-12345"`, `"2025-12"` |
| `int` / `long` | 整數，用於計數、Token 數 | `150`, `1234567` |
| `double` | 浮點數，用於百分比 | `76.12` |
| `boolean` | 布林值 | `true`, `false` |
| `BigDecimal` | 精確小數，用於金額 (避免浮點誤差) | `45.67` |
| `Instant` | UTC 時間戳記 | `2025-12-19T14:30:00Z` |
| `Map<String, ?>` | 鍵值對，用於動態欄位 | `{"claude-sonnet-4": 200000}` |

#### 禁止的類型

| 類型 | 替代方案 |
|------|----------|
| `YearMonth` | 使用 `String` ("2025-12") + `int` (year, month) |
| `LocalDate` | 使用 `Instant` (UTC) 或 `String` ("2025-12-01") |
| `QuotaPeriod` enum | 移除，統一月度 |
| `QuotaConfig` record | 扁平化欄位直接放在 Document |
| 其他自定義 Class/Record | 扁平化或使用 Map |

### 3.2 UserQuota Document（用戶當月配額狀態）

#### 欄位說明表

| 欄位 | 類型 | 用途 | 範例值 |
|------|------|------|--------|
| **基本識別** ||||
| `id` | String | MongoDB 自動產生的文件 ID | `"507f1f77bcf86cd799439011"` |
| `userId` | String | 用戶唯一識別碼，對應 Gate 的 user ID | `"user-uuid-12345"` |
| **週期標識** ||||
| `periodYear` | int | 當前配額週期的年份 | `2025` |
| `periodMonth` | int | 當前配額週期的月份 (1-12) | `12` |
| `periodStartAt` | Instant | 週期開始時間 (UTC)，該月 1 號 00:00:00 | `2025-12-01T00:00:00Z` |
| `periodEndAt` | Instant | 週期結束時間 (UTC)，該月最後一天 23:59:59 | `2025-12-31T23:59:59.999Z` |
| **累計統計（全歷史，不重置）** ||||
| `totalInputTokens` | long | 歷史累計輸入 Token 數，用於分析用戶總體使用趨勢 | `1234567` |
| `totalOutputTokens` | long | 歷史累計輸出 Token 數 | `567890` |
| `totalTokens` | long | 歷史累計總 Token 數 | `1802457` |
| `totalRequestCount` | long | 歷史累計請求次數 | `890` |
| `totalEstimatedCostUsd` | BigDecimal | 歷史累計成本 (USD)，用於分析用戶總花費 | `156.78` |
| **配額設定（管理員設定）** ||||
| `quotaEnabled` | boolean | 是否啟用配額限制，false = 無限制 | `true` |
| `costLimitUsd` | BigDecimal | 月度成本上限 (USD)，0 = 無限制 | `50.00` |
| **當期用量（每月重置）** ||||
| `periodInputTokens` | long | 當月輸入 Token 數 | `234567` |
| `periodOutputTokens` | long | 當月輸出 Token 數 | `123456` |
| `periodTokens` | long | 當月總 Token 數 | `358023` |
| `periodCostUsd` | BigDecimal | 當月已使用成本 (USD) | `45.67` |
| `periodRequestCount` | int | 當月請求次數 | `150` |
| **額外額度（每月重置）** ||||
| `bonusCostUsd` | BigDecimal | 管理員額外給予的 USD 額度，與 costLimitUsd 相加為有效上限 | `10.00` |
| `bonusReason` | String | 給予額外額度的原因，供稽核查閱 | `"專案衝刺需要"` |
| `bonusGrantedAt` | Instant | 最後一次給予額外額度的時間 | `2025-12-15T10:30:00Z` |
| **配額狀態（計算欄位）** ||||
| `costUsagePercent` | double | 成本使用率 = periodCostUsd / (costLimitUsd + bonusCostUsd) * 100，用於 UI 圓形進度條 | `76.12` |
| `quotaExceeded` | boolean | 是否已超額 (costUsagePercent >= 100) | `false` |
| **時間戳記（可選）** ||||
| `firstSeenAt` | Instant | 用戶首次出現時間，用於分析用戶生命週期 | `2025-01-15T08:30:00Z` |
| `lastActiveAt` | Instant | 用戶最後活動時間，用於識別閒置用戶 | `2025-12-19T14:25:00Z` |
| `lastUpdatedAt` | Instant | 文件最後更新時間，用於除錯和稽核 | `2025-12-19T14:30:00Z` |

#### 時間戳記欄位詳細定義

| 欄位 | 定義 | 更新時機 | 用途 |
|------|------|----------|------|
| `firstSeenAt` | 用戶首次出現在系統中的 UTC 時間 | **僅在建立 UserQuota 時設定一次**，之後不再更新 | 用戶生命週期分析：區分新/舊用戶、計算用戶使用天數 |
| `lastActiveAt` | 用戶最後一次發送 LLM 請求的 UTC 時間 | **每次 Settlement 處理該用戶事件時更新** | 閒置用戶識別：找出超過 N 天未使用的用戶 |
| `lastUpdatedAt` | 此 Document 最後被修改的 UTC 時間 | **任何欄位變更時更新**（Settlement、給予額外額度、修改配額設定） | 除錯與稽核：追蹤資料變更時間點 |

**更新邏輯範例**：
```java
// 建立新用戶時
.setOnInsert("firstSeenAt", Instant.now())

// 每次處理用量事件時
.set("lastActiveAt", Instant.now())
.set("lastUpdatedAt", Instant.now())

// 修改配額設定時（無用量事件）
.set("lastUpdatedAt", Instant.now())
// lastActiveAt 不更新（因為不是用戶活動）
```

#### Document 定義

```java
@Document(collection = "user_quota")
public record UserQuota(
    @Id String id,

    // ========== 基本識別 ==========
    String userId,

    // ========== 週期標識 ==========
    int periodYear,           // 2025
    int periodMonth,          // 12 (1-12)
    Instant periodStartAt,
    Instant periodEndAt,

    // ========== 累計統計（全歷史，不重置）==========
    long totalInputTokens,
    long totalOutputTokens,
    long totalTokens,
    long totalRequestCount,
    BigDecimal totalEstimatedCostUsd,

    // ========== 配額設定 ==========
    boolean quotaEnabled,
    BigDecimal costLimitUsd,

    // ========== 當期用量（每月重置）==========
    long periodInputTokens,
    long periodOutputTokens,
    long periodTokens,
    BigDecimal periodCostUsd,
    int periodRequestCount,

    // ========== 額外額度（每月重置）==========
    BigDecimal bonusCostUsd,
    String bonusReason,
    Instant bonusGrantedAt,

    // ========== 配額狀態 ==========
    double costUsagePercent,
    boolean quotaExceeded,

    // ========== 時間戳記 ==========
    Instant firstSeenAt,      // 可選：用戶生命週期分析
    Instant lastActiveAt,     // 可選：閒置用戶識別
    Instant lastUpdatedAt     // 建議：除錯與稽核
) {
    // 計算方法...
}
```

#### 範例 Document (MongoDB)

```json
{
  "_id": "507f1f77bcf86cd799439011",
  "userId": "user-uuid-12345",

  "periodYear": 2025,
  "periodMonth": 12,
  "periodStartAt": "2025-12-01T00:00:00Z",
  "periodEndAt": "2025-12-31T23:59:59.999Z",

  "totalInputTokens": 1234567,
  "totalOutputTokens": 567890,
  "totalTokens": 1802457,
  "totalRequestCount": 890,
  "totalEstimatedCostUsd": 156.78,

  "quotaEnabled": true,
  "costLimitUsd": 50.00,

  "periodInputTokens": 234567,
  "periodOutputTokens": 123456,
  "periodTokens": 358023,
  "periodCostUsd": 45.67,
  "periodRequestCount": 150,

  "bonusCostUsd": 10.00,
  "bonusReason": "專案衝刺需要",
  "bonusGrantedAt": "2025-12-15T10:30:00Z",

  "costUsagePercent": 76.12,
  "quotaExceeded": false,

  "firstSeenAt": "2025-01-15T08:30:00Z",
  "lastActiveAt": "2025-12-19T14:25:00Z",
  "lastUpdatedAt": "2025-12-19T14:30:00Z"
}
```

### 3.3 QuotaHistory Document（月度歷史記錄）

用途：當月份結束時，將 UserQuota 的當期資料歸檔保存，供歷史查詢和分析。

#### Document 定義

```java
@Document(collection = "quota_history")
public record QuotaHistory(
    @Id String id,
    String userId,
    int periodYear,
    int periodMonth,
    long totalInputTokens,
    long totalOutputTokens,
    long totalTokens,
    BigDecimal totalCostUsd,
    int totalRequestCount,
    BigDecimal costLimitUsd,
    BigDecimal bonusCostUsd,
    BigDecimal effectiveLimitUsd,
    double finalUsagePercent,
    boolean wasExceeded,
    Map<String, Long> modelTokens,
    Map<String, BigDecimal> modelCosts,
    Instant archivedAt
) {}
```

#### 欄位說明表

| 欄位 | 類型 | 用途 | 範例值 |
|------|------|------|--------|
| **基本識別** ||||
| `id` | String | MongoDB 自動產生的文件 ID | `"507f1f77bcf86cd799439012"` |
| `userId` | String | 用戶唯一識別碼 | `"user-uuid-12345"` |
| **週期標識** ||||
| `periodYear` | int | 歸檔的年份 | `2025` |
| `periodMonth` | int | 歸檔的月份 (1-12) | `11` |
| **該月統計** ||||
| `totalInputTokens` | long | 該月輸入 Token 數 | `234567` |
| `totalOutputTokens` | long | 該月輸出 Token 數 | `123456` |
| `totalTokens` | long | 該月總 Token 數 | `358023` |
| `totalCostUsd` | BigDecimal | 該月總成本 (USD) | `45.67` |
| `totalRequestCount` | int | 該月請求次數 | `150` |
| **配額資訊（當時設定）** ||||
| `costLimitUsd` | BigDecimal | 當月基礎配額設定 | `50.00` |
| `bonusCostUsd` | BigDecimal | 當月額外額度 | `10.00` |
| `effectiveLimitUsd` | BigDecimal | 當月有效配額 = costLimitUsd + bonusCostUsd | `60.00` |
| `finalUsagePercent` | double | 月底最終使用率 | `76.12` |
| `wasExceeded` | boolean | 該月是否曾經超額 | `false` |
| **模型使用分布（分析用）** ||||
| `modelTokens` | Map | 各模型使用的 Token 數，用於分析模型偏好 | `{"claude-sonnet-4": 200000, "claude-haiku": 158023}` |
| `modelCosts` | Map | 各模型產生的成本 | `{"claude-sonnet-4": 25.00, "claude-haiku": 20.67}` |
| **時間戳記** ||||
| `archivedAt` | Instant | 歸檔執行時間 | `2025-12-01T00:00:05Z` |

#### 範例 Document (MongoDB)

```json
{
  "_id": "507f1f77bcf86cd799439012",
  "userId": "user-uuid-12345",
  "periodYear": 2025,
  "periodMonth": 11,
  "totalInputTokens": 234567,
  "totalOutputTokens": 123456,
  "totalTokens": 358023,
  "totalCostUsd": 45.67,
  "totalRequestCount": 150,
  "costLimitUsd": 50.00,
  "bonusCostUsd": 10.00,
  "effectiveLimitUsd": 60.00,
  "finalUsagePercent": 76.12,
  "wasExceeded": false,
  "modelTokens": {
    "claude-sonnet-4-20250514": 200000,
    "claude-haiku-3-5-20241022": 158023
  },
  "modelCosts": {
    "claude-sonnet-4-20250514": 25.00,
    "claude-haiku-3-5-20241022": 20.67
  },
  "archivedAt": "2025-12-01T00:00:05Z"
}
```

### 3.4 BonusRecord Document（額外額度記錄）

用途：記錄管理員給予用戶額外額度的操作，供稽核追蹤。簡化設計，無審核流程。

#### Document 定義

```java
@Document(collection = "bonus_records")
public record BonusRecord(
    @Id String id,
    String userId,
    int periodYear,
    int periodMonth,
    BigDecimal amount,
    String reason,
    String grantedBy,
    Instant createdAt
) {}
```

#### 欄位說明表

| 欄位 | 類型 | 用途 | 範例值 |
|------|------|------|--------|
| **基本識別** ||||
| `id` | String | MongoDB 自動產生的文件 ID | `"507f1f77bcf86cd799439013"` |
| `userId` | String | 接收額外額度的用戶 ID | `"user-uuid-12345"` |
| **週期標識** ||||
| `periodYear` | int | 給予額度的年份 | `2025` |
| `periodMonth` | int | 給予額度的月份 (1-12) | `12` |
| **額度資訊** ||||
| `amount` | BigDecimal | 給予的額度金額 (USD) | `10.00` |
| `reason` | String | 給予原因，供稽核查閱 | `"專案衝刺需要額外資源"` |
| `grantedBy` | String | 執行給予操作的管理員 ID 或名稱 | `"admin@example.com"` |
| **時間戳記** ||||
| `createdAt` | Instant | 記錄建立時間 | `2025-12-15T10:30:00Z` |

#### 範例 Document (MongoDB)

```json
{
  "_id": "507f1f77bcf86cd799439013",
  "userId": "user-uuid-12345",
  "periodYear": 2025,
  "periodMonth": 12,
  "amount": 10.00,
  "reason": "專案衝刺需要額外資源",
  "grantedBy": "admin@example.com",
  "createdAt": "2025-12-15T10:30:00Z"
}
```

---

## 4. Data Flow

### 4.1 Overall Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Quota System Data Flow                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │                  Settlement 觸發的配額更新流程                        │   │
│   │                                                                     │   │
│   │   POST /settlement/trigger                                          │   │
│   │           │                                                         │   │
│   │           ▼                                                         │   │
│   │   ┌───────────────────┐                                             │   │
│   │   │ BatchSettlement   │                                             │   │
│   │   │ Service           │                                             │   │
│   │   └─────────┬─────────┘                                             │   │
│   │             │                                                       │   │
│   │             ▼                                                       │   │
│   │   ┌───────────────────┐    ┌──────────────────────────────────────┐│   │
│   │   │ UsageAggregation  │───▶│  updateUserQuota() 處理邏輯：        ││   │
│   │   │ Service           │    │                                      ││   │
│   │   └───────────────────┘    │  1. 查詢用戶 UserQuota               ││   │
│   │                            │                                      ││   │
│   │                            │  2. 用戶不存在？                      ││   │
│   │                            │     → 建立新 UserQuota（當前週期）    ││   │
│   │                            │                                      ││   │
│   │                            │  3. 週期不一致？                      ││   │
│   │                            │     (periodYear/Month ≠ 當前年月)    ││   │
│   │                            │     → 歸檔至 quota_history            ││   │
│   │                            │     → 重置週期欄位                    ││   │
│   │                            │                                      ││   │
│   │                            │  4. 累加當期用量                      ││   │
│   │                            │     → periodCostUsd += cost          ││   │
│   │                            │     → periodTokens += tokens         ││   │
│   │                            │     → 重算使用率                      ││   │
│   │                            └──────────────────────────────────────┘│   │
│   └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │                        額外額度給予流程                               │   │
│   │                                                                     │   │
│   │   Admin API            BonusRecord            UserQuota             │   │
│   │   (給予額度)            (記錄)               (更新配額)             │   │
│   │       │                    │                      │                 │   │
│   │       ▼                    ▼                      ▼                 │   │
│   │   ┌──────────┐        ┌──────────┐          ┌──────────┐           │   │
│   │   │ POST     │ ─────▶ │ 新增記錄 │ ───────▶ │ 累加     │           │   │
│   │   │ /bonus   │        │          │          │ bonusCost│           │   │
│   │   └──────────┘        └──────────┘          │ 重算使用率│           │   │
│   │                                             └──────────┘           │   │
│   └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Settlement-Triggered Quota Update

**設計理念**：配額更新整合於 Settlement 流程，而非獨立的 Cron Job。

優點：
1. **簡化架構**：不需要額外的排程服務
2. **即時性**：資料處理時自動檢查並重置週期
3. **容錯性**：即使伺服器在月初停機，下次 Settlement 時會自動處理
4. **一致性**：配額更新與用量聚合在同一交易中完成

```
Settlement 觸發時機：
├── 自動：每小時整點 (@Scheduled cron = "0 0 * * * *")
└── 手動：POST /api/v1/usage/settlement/trigger
```

### 4.3 Period Identification

```
週期標識使用兩個整數欄位：periodYear + periodMonth

範例：
- periodYear = 2025
- periodMonth = 12
- periodStartAt = 2025-12-01T00:00:00Z
- periodEndAt = 2025-12-31T23:59:59.999Z

工具方法：
public static int getCurrentYear() {
    return LocalDate.now(ZoneOffset.UTC).getYear();
}

public static int getCurrentMonth() {
    return LocalDate.now(ZoneOffset.UTC).getMonthValue();
}

public static Instant getPeriodStart(int year, int month) {
    return LocalDate.of(year, month, 1)
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant();
}

public static Instant getPeriodEnd(int year, int month) {
    LocalDate lastDay = LocalDate.of(year, month, 1)
        .withDayOfMonth(LocalDate.of(year, month, 1).lengthOfMonth());
    return lastDay.atTime(23, 59, 59, 999_000_000)
        .atZone(ZoneOffset.UTC)
        .toInstant();
}

// 週期比較
public static boolean isSamePeriod(int year1, int month1, int year2, int month2) {
    return year1 == year2 && month1 == month2;
}
```

---

## 5. Period Reset (Settlement-Triggered)

### 5.1 設計理念

**不使用獨立的 Cron Job**，改為在 Settlement 處理時自動檢查並重置週期。

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     Period Reset 觸發邏輯                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   Settlement 執行時 (每小時或手動觸發)                                       │
│       │                                                                     │
│       ▼                                                                     │
│   ┌──────────────────────────────────────────────────────────────────────┐  │
│   │  UsageAggregationService.updateUserQuota()                           │  │
│   │                                                                      │  │
│   │  for each userId in batch:                                           │  │
│   │      │                                                               │  │
│   │      ├─ 查詢 UserQuota                                               │  │
│   │      │                                                               │  │
│   │      ├─ 用戶不存在？ ────────────────────────────┐                   │  │
│   │      │                                          ▼                    │  │
│   │      │                               ┌────────────────────┐          │  │
│   │      │                               │ 建立新 UserQuota   │          │  │
│   │      │                               │ periodYear/Month = │          │  │
│   │      │                               │   當前年月         │          │  │
│   │      │                               └────────────────────┘          │  │
│   │      │                                                               │  │
│   │      ├─ periodYear/Month ≠ 當前年月？ ────────────┐                  │  │
│   │      │                                           ▼                   │  │
│   │      │                               ┌────────────────────┐          │  │
│   │      │                               │ 1. 歸檔至          │          │  │
│   │      │                               │    quota_history   │          │  │
│   │      │                               │ 2. 重置週期欄位    │          │  │
│   │      │                               └────────────────────┘          │  │
│   │      │                                                               │  │
│   │      └─▶ 累加當期用量，重算使用率                                      │  │
│   │                                                                      │  │
│   └──────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 優點

| 優點 | 說明 |
|------|------|
| **簡化架構** | 不需要額外的 QuotaPeriodResetService |
| **容錯性佳** | 即使月初伺服器停機，下次 Settlement 時自動處理 |
| **即時重置** | 用戶資料在被處理時立即重置 |
| **無遺漏** | 只有有用量的用戶才會被處理和歸檔 |

### 5.3 Period Reset 邏輯（整合於 UsageAggregationService）

```java
/**
 * 更新用戶配額（含週期重置邏輯）
 */
private void updateUserQuota(List<UsageEventData> events) {
    int currentYear = getCurrentYear();
    int currentMonth = getCurrentMonth();

    Map<String, List<UsageEventData>> grouped = events.stream()
        .collect(Collectors.groupingBy(UsageEventData::userId));

    for (Map.Entry<String, List<UsageEventData>> entry : grouped.entrySet()) {
        String userId = entry.getKey();
        List<UsageEventData> userEvents = entry.getValue();

        // 計算本批次用量
        long inputTokens = userEvents.stream().mapToLong(UsageEventData::totalInputTokens).sum();
        long outputTokens = userEvents.stream().mapToLong(UsageEventData::outputTokens).sum();
        long totalTokens = userEvents.stream().mapToLong(UsageEventData::totalTokens).sum();
        BigDecimal cost = costService.calculateBatchCost(userEvents);

        // 查詢現有 UserQuota
        Optional<UserQuota> existing = userQuotaRepository.findByUserId(userId);

        if (existing.isEmpty()) {
            // ========== 用戶不存在：建立新 UserQuota ==========
            createNewUserQuota(userId, currentYear, currentMonth,
                              inputTokens, outputTokens, totalTokens, cost, userEvents.size());
        } else {
            UserQuota quota = existing.get();

            if (!isSamePeriod(quota.periodYear(), quota.periodMonth(), currentYear, currentMonth)) {
                // ========== 週期不一致：歸檔 + 重置 + 更新 ==========
                archiveAndResetPeriod(quota, currentYear, currentMonth,
                    inputTokens, outputTokens, totalTokens, cost, userEvents.size());
            } else {
                // ========== 週期一致：直接累加 ==========
                incrementUsage(quota, inputTokens, outputTokens,
                              totalTokens, cost, userEvents.size());
            }
        }
    }
}

/**
 * 歸檔舊週期資料並重置為新週期
 */
private void archiveAndResetPeriod(UserQuota quota, int newYear, int newMonth,
        long inputTokens, long outputTokens, long totalTokens,
        BigDecimal cost, int requestCount) {

    int oldYear = quota.periodYear();
    int oldMonth = quota.periodMonth();

    // 1. 歸檔至 quota_history（如有用量）
    if (quota.periodRequestCount() > 0) {
        Map<String, Long> modelTokens = aggregateModelTokens(quota.userId(), oldYear, oldMonth);
        Map<String, BigDecimal> modelCosts = aggregateModelCosts(quota.userId(), oldYear, oldMonth);

        QuotaHistory history = new QuotaHistory(
            null,  // ID 自動產生
            quota.userId(),
            oldYear,
            oldMonth,
            quota.periodInputTokens(),
            quota.periodOutputTokens(),
            quota.periodTokens(),
            quota.periodCostUsd(),
            quota.periodRequestCount(),
            quota.costLimitUsd(),
            quota.bonusCostUsd(),
            quota.getEffectiveCostLimit(),
            quota.costUsagePercent(),
            quota.quotaExceeded(),
            modelTokens,
            modelCosts,
            Instant.now()
        );
        quotaHistoryRepository.save(history);
        log.info("Archived quota history: userId={}, period={}-{}", quota.userId(), oldYear, oldMonth);
    }

    // 2. 重置週期並設定新用量
    Instant now = Instant.now();
    userQuotaRepository.resetPeriodAndSetUsageByUserId(
        quota.userId(),
        newYear, newMonth,
        getPeriodStart(newYear, newMonth),
        getPeriodEnd(newYear, newMonth),
        inputTokens, outputTokens, totalTokens,
        cost, requestCount,
        now
    );

    // 3. 重算使用率（此時配額已重置，使用率應接近 0）
    userQuotaRepository.updateQuotaStatusByUserId(quota.userId(), 0.0, false, now);

    log.info("Period reset: userId={}, {}-{} -> {}-{}", quota.userId(), oldYear, oldMonth, newYear, newMonth);
}

/**
 * 建立新用戶的 UserQuota
 */
private void createNewUserQuota(String userId, int year, int month,
        long inputTokens, long outputTokens, long totalTokens,
        BigDecimal cost, int requestCount) {

    UserQuota newQuota = new UserQuota(
        null,  // ID 自動產生
        userId,
        year,
        month,
        getPeriodStart(year, month),
        getPeriodEnd(year, month),
        // 累計統計 = 本批次值
        inputTokens, outputTokens, totalTokens, requestCount, cost,
        // 配額設定（預設）
        false, BigDecimal.ZERO,
        // 當期用量 = 本批次值
        inputTokens, outputTokens, totalTokens, cost, requestCount,
        // 額外額度
        BigDecimal.ZERO, null, null,
        // 配額狀態
        0.0, false,
        // 時間戳
        Instant.now(), Instant.now(), Instant.now()
    );

    userQuotaRepository.save(newQuota);
    log.info("Created new UserQuota: userId={}, period={}-{}", userId, year, month);
}

/**
 * 累加用量（週期一致時）
 */
private void incrementUsage(UserQuota quota,
        long inputTokens, long outputTokens, long totalTokens,
        BigDecimal cost, int requestCount) {

    Instant now = Instant.now();

    // 累加用量（同時更新當期用量和總計）
    userQuotaRepository.incrementUsageByUserId(
        quota.userId(),
        inputTokens, outputTokens, totalTokens,
        cost, requestCount,
        now
    );

    // 重算使用率
    recalculateUsagePercent(quota.userId(), now);
}
```

---

## 6. Bonus (Extra Quota) System

### 6.1 Simplified Design

不需要審核流程，管理員直接給予額外額度：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      Bonus Grant Flow (簡化版)                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   Admin                                                   System            │
│                                                                             │
│   ┌─────────────────┐                                                       │
│   │ POST /api/v1/   │                                                       │
│   │ quota/users/    │                                                       │
│   │ {userId}/bonus  │                                                       │
│   │                 │                                                       │
│   │ {               │                                                       │
│   │   "amount": 20, │                                                       │
│   │   "reason": "…" │                                                       │
│   │ }               │                                                       │
│   └────────┬────────┘                                                       │
│            │                                                                │
│            ▼                                                                │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │  1. 建立 BonusRecord 記錄                                            │   │
│   │     - userId, periodYear, periodMonth, amount, reason, grantedBy    │   │
│   └─────────────────────────────────────────────────────────────────────┘   │
│            │                                                                │
│            ▼                                                                │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │  2. 更新 UserQuota                                                   │   │
│   │     - bonusCostUsd += amount                                        │   │
│   │     - bonusReason = reason                                          │   │
│   │     - bonusGrantedAt = now                                          │   │
│   │     - 重新計算 costUsagePercent                                      │   │
│   └─────────────────────────────────────────────────────────────────────┘   │
│            │                                                                │
│            ▼                                                                │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │  3. 回傳更新後的 UserQuota                                           │   │
│   └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 6.2 BonusService

```java
@Service
@RequiredArgsConstructor
public class BonusService {

    private final BonusRecordRepository bonusRecordRepository;
    private final UserQuotaRepository userQuotaRepository;

    /**
     * 給予用戶額外額度
     */
    @Transactional
    public UserQuota grantBonus(
            String userId,
            BigDecimal amount,
            String reason,
            String grantedBy) {

        int currentYear = getCurrentYear();
        int currentMonth = getCurrentMonth();
        Instant now = Instant.now();

        // 1. 建立記錄
        BonusRecord record = new BonusRecord(
            null,  // ID 自動產生
            userId,
            currentYear,
            currentMonth,
            amount,
            reason,
            grantedBy,
            now
        );
        bonusRecordRepository.save(record);

        // 2. 查詢現有配額以計算使用率
        UserQuota quota = userQuotaRepository.findByUserId(userId)
            .orElseThrow(() -> new NotFoundException("User not found: " + userId));

        // 3. 累加額外額度
        userQuotaRepository.addBonusByUserId(userId, amount, reason, now);

        // 4. 重算使用率
        BigDecimal newBonus = (quota.bonusCostUsd() != null ? quota.bonusCostUsd() : BigDecimal.ZERO)
            .add(amount);
        BigDecimal effectiveLimit = quota.costLimitUsd().add(newBonus);
        double usagePercent = effectiveLimit.compareTo(BigDecimal.ZERO) > 0
            ? quota.periodCostUsd()
                .divide(effectiveLimit, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue()
            : 0.0;

        userQuotaRepository.updateQuotaStatusByUserId(userId, usagePercent, usagePercent >= 100, now);

        return userQuotaRepository.findByUserId(userId).orElse(quota);
    }

    /**
     * 查詢用戶的額外額度記錄
     */
    public List<BonusRecord> getUserBonusHistory(String userId) {
        return bonusRecordRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    private int getCurrentYear() {
        return LocalDate.now(ZoneOffset.UTC).getYear();
    }

    private int getCurrentMonth() {
        return LocalDate.now(ZoneOffset.UTC).getMonthValue();
    }
}
```

---

## 7. API Design

### 7.1 Quota Status API

```
GET /api/v1/quota/users/{userId}
```

**Response**:
```json
{
  "id": "507f1f77bcf86cd799439011",
  "userId": "user-uuid-12345",

  "period": {
    "yearMonth": "2025-12",
    "startAt": "2025-12-01T00:00:00Z",
    "endAt": "2025-12-31T23:59:59Z",
    "daysRemaining": 12
  },

  "quota": {
    "enabled": true,
    "baseLimitUsd": 50.00,
    "bonusUsd": 10.00,
    "effectiveLimitUsd": 60.00
  },

  "usage": {
    "costUsd": 45.67,
    "inputTokens": 234567,
    "outputTokens": 123456,
    "totalTokens": 358023,
    "requestCount": 150
  },

  "status": {
    "usagePercent": 76.12,
    "remainingUsd": 14.33,
    "exceeded": false,
    "level": "WARNING"
  },

  "bonus": {
    "amount": 10.00,
    "reason": "專案需求",
    "grantedAt": "2025-12-15T10:30:00Z"
  },

  "totals": {
    "allTimeTokens": 1234567,
    "allTimeCostUsd": 156.78,
    "allTimeRequests": 890
  }
}
```

### 7.2 Quota Management APIs

```
# 設定用戶配額
PUT /api/v1/quota/users/{userId}/config
{
  "enabled": true,
  "costLimitUsd": 100.00
}

# 取得所有用戶配額列表
GET /api/v1/quota/users?page=0&size=20

# 取得超額用戶列表
GET /api/v1/quota/users?exceeded=true

# 給予額外額度
POST /api/v1/quota/users/{userId}/bonus
{
  "amount": 20.00,
  "reason": "月底專案衝刺需要額外額度"
}
```

### 7.3 History API

```
# 取得用戶歷史配額使用記錄
GET /api/v1/quota/users/{userId}/history?months=6

Response:
{
  "userId": "user-uuid-12345",
  "history": [
    {
      "id": "507f1f77bcf86cd799439012",
      "periodYear": 2025,
      "periodMonth": 12,
      "usage": {
        "totalTokens": 358023,
        "totalCostUsd": 45.67,
        "requestCount": 150
      },
      "quota": {
        "limitUsd": 50.00,
        "bonusUsd": 10.00,
        "effectiveLimitUsd": 60.00
      },
      "finalUsagePercent": 76.12,
      "wasExceeded": false,
      "modelBreakdown": {
        "claude-sonnet-4-5-20250929": {
          "tokens": 200000,
          "costUsd": 25.00
        },
        "claude-haiku-3-5-20241022": {
          "tokens": 158023,
          "costUsd": 20.67
        }
      },
      "archivedAt": "2026-01-01T00:00:05Z"
    }
  ]
}

# 取得用戶額外額度記錄
GET /api/v1/quota/users/{userId}/bonus-history

Response:
{
  "userId": "user-uuid-12345",
  "records": [
    {
      "id": "507f1f77bcf86cd799439013",
      "periodYear": 2025,
      "periodMonth": 12,
      "amount": 10.00,
      "reason": "專案需求",
      "grantedBy": "admin@example.com",
      "createdAt": "2025-12-15T10:30:00Z"
    }
  ]
}
```

---

## 8. UI Design - Quota Visualization

### 8.1 Usage Level Thresholds

配額使用率會在 UI 上以顏色指示，方便用戶快速理解狀態：

| 使用率 | Level | 顏色 | 說明 |
|--------|-------|------|------|
| < 50% | `OK` | 綠色 (#10b981) | 正常使用 |
| 50% ~ 79% | `WARNING` | 黃色 (#f59e0b) | 接近限額 |
| 80% ~ 99% | `CRITICAL` | 橘色 (#f97316) | 即將超額 |
| >= 100% | `EXCEEDED` | 紅色 (#ef4444) | 已超額 |

### 8.2 Circular Progress Indicator

參考 Antigravity 配額監控的設計，在 `user-detail.html` 以圓形進度條呈現：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Quota Status Card                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐         │
│   │                  │  │                  │  │                  │         │
│   │    ┌──────┐      │  │    ┌──────┐      │  │                  │         │
│   │   ╱        ╲     │  │   ╱        ╲     │  │       12         │         │
│   │  │  76.1%   │    │  │  │  45.6%   │    │  │                  │         │
│   │   ╲        ╱     │  │   ╲        ╱     │  │   Days Left      │         │
│   │    └──────┘      │  │    └──────┘      │  │                  │         │
│   │                  │  │                  │  │   Resets:        │         │
│   │   Cost Usage     │  │  Token Usage     │  │   MONTHLY        │         │
│   │  $45.67 / $60    │  │  358K / ∞        │  │                  │         │
│   └──────────────────┘  └──────────────────┘  └──────────────────┘         │
│                                                                             │
│   Period: 2025-12-01 ~ 2025-12-31                      [ Within Limits ✓ ] │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 8.3 SVG Circle Implementation

現有的 `user-detail.html` 已實作圓形進度條：

```html
<!-- 圓形進度條 SVG -->
<svg class="w-24 h-24 transform -rotate-90">
    <!-- 背景圓 -->
    <circle cx="48" cy="48" r="40" stroke="#e5e7eb" stroke-width="8" fill="none"/>
    <!-- 進度圓（顏色根據使用率動態變化）-->
    <circle cx="48" cy="48" r="40"
            th:attr="stroke=${quota.costUsagePercent() >= 80 ? '#ef4444' :
                            (quota.costUsagePercent() >= 50 ? '#f59e0b' : '#10b981')}"
            stroke-width="8" fill="none"
            stroke-linecap="round"
            th:style="'stroke-dasharray: 251; stroke-dashoffset: ' +
                      ${251 - (251 * T(Math).min(100, quota.costUsagePercent()) / 100)}"/>
</svg>
<span class="absolute text-xl font-bold"
      th:text="${#numbers.formatDecimal(quota.costUsagePercent(), 1, 0) + '%'}">0%</span>
```

### 8.4 Color Logic

```java
/**
 * 根據使用率計算 UI 顯示等級
 */
public String getUsageLevel(double usagePercent) {
    if (usagePercent >= 100) return "EXCEEDED";
    if (usagePercent >= 80) return "CRITICAL";
    if (usagePercent >= 50) return "WARNING";
    return "OK";
}

/**
 * 根據等級返回 Tailwind CSS 顏色類
 */
public String getColorClass(String level) {
    return switch (level) {
        case "EXCEEDED" -> "text-red-600";
        case "CRITICAL" -> "text-orange-500";
        case "WARNING" -> "text-yellow-500";
        default -> "text-green-500";
    };
}
```

### 8.5 Dashboard Display Components

在 Dashboard 中顯示的資訊：

1. **圓形進度條**：使用率百分比 + 動態顏色
2. **數值顯示**：已用金額 / 配額上限
3. **剩餘天數**：週期結束倒數
4. **狀態標籤**：Within Limits / Quota Exceeded
5. **重置時間**：下次重置時間（每月 1 號）

---

## 9. Integration with UsageAggregationService

### 9.1 整合概述

配額更新邏輯整合於 `UsageAggregationService.updateUserQuota()` 方法中。
詳細實作請參考 **Section 5.3 Period Reset 邏輯**。

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                  UsageAggregationService.processBatch()                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  public void processBatch(List<UsageEventData> events) {                    │
│      updateDailyUserUsage(events);      // → daily_user_usage               │
│      updateDailyModelUsage(events);     // → daily_model_usage              │
│      updateUserQuota(events);           // → user_quota (含週期重置)        │
│      updateSystemStats(events);         // → system_stats                   │
│  }                                                                          │
│                                                                             │
│  updateUserQuota() 處理邏輯：                                               │
│  ├─ 用戶不存在 → createNewUserQuota()                                       │
│  ├─ 週期不一致 → archiveAndResetPeriod()                                   │
│  └─ 週期一致   → incrementUsage()                                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 9.2 recalculateUsagePercent 方法

```java
/**
 * 重新計算用戶的配額使用率
 */
private void recalculateUsagePercent(String userId, Instant now) {
    UserQuota quota = userQuotaRepository.findByUserId(userId).orElse(null);
    if (quota == null || !quota.quotaEnabled()) {
        return;
    }

    // 計算使用率
    BigDecimal effectiveLimit = quota.getEffectiveCostLimit();
    double usagePercent = 0.0;
    boolean exceeded = false;

    if (effectiveLimit.compareTo(BigDecimal.ZERO) > 0) {
        usagePercent = quota.periodCostUsd()
            .divide(effectiveLimit, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100))
            .doubleValue();
        exceeded = usagePercent >= 100;
    }

    // 透過 Repository 更新狀態
    userQuotaRepository.updateQuotaStatusByUserId(userId, usagePercent, exceeded, now);
}
```

---

## 10. Configuration

```yaml
# application.yaml
ledger:
  quota:
    # 預設配額設定（新用戶）
    default-enabled: false
    default-cost-limit-usd: 0           # 0 = 無限制

    # 週期重置設定
    reset:
      timezone: UTC
      cron: "0 0 0 1 * *"               # 每月 1 號 00:00:00 UTC
```

---

## 11. Repository Interfaces

使用 Spring Data MongoDB 的 Repository 介面，透過 Derived Query Methods 和 @Query/@Update 註解簡化操作。

### 11.1 UserQuotaRepository

```java
public interface UserQuotaRepository extends MongoRepository<UserQuota, String> {

    // ========== 基本查詢 (Derived Query Methods) ==========

    /**
     * 根據用戶 ID 查詢配額記錄
     * @param userId 用戶唯一識別碼
     * @return 用戶配額記錄（如存在）
     */
    Optional<UserQuota> findByUserId(String userId);

    /**
     * 檢查用戶是否已有配額記錄
     * @param userId 用戶唯一識別碼
     * @return true 表示已存在配額記錄
     */
    boolean existsByUserId(String userId);

    /**
     * 查詢所有啟用配額限制的用戶
     * @return 啟用配額的用戶清單
     */
    List<UserQuota> findByQuotaEnabledTrue();

    /**
     * 查詢所有已超額的用戶（用於管理介面告警）
     * @return 已超額的用戶清單
     */
    List<UserQuota> findByQuotaExceededTrue();

    /**
     * 查詢指定週期的所有用戶配額（用於報表統計）
     * @param periodYear 年份
     * @param periodMonth 月份 (1-12)
     * @return 該週期的用戶配額清單
     */
    List<UserQuota> findByPeriodYearAndPeriodMonth(int periodYear, int periodMonth);

    // ========== 分頁查詢 ==========

    /**
     * 分頁查詢所有用戶，按最後活動時間降序排列（用於管理介面）
     * @param pageable 分頁參數
     * @return 分頁結果
     */
    Page<UserQuota> findAllByOrderByLastActiveAtDesc(Pageable pageable);

    /**
     * 分頁查詢啟用配額的用戶，按使用率降序排列（用於監控高用量用戶）
     * @param pageable 分頁參數
     * @return 分頁結果
     */
    Page<UserQuota> findByQuotaEnabledTrueOrderByCostUsagePercentDesc(Pageable pageable);

    // ========== 複合條件查詢 (@Query) ==========

    /**
     * 查詢已啟用配額且已超額的用戶（需要關注的用戶）
     * @return 已超額的用戶清單
     */
    @Query("{ 'quotaEnabled': true, 'quotaExceeded': true }")
    List<UserQuota> findEnabledAndExceeded();

    /**
     * 查詢使用率達到指定閾值的用戶（用於預警通知）
     * @param percent 使用率閾值（如 80.0 表示 80%）
     * @return 達到閾值的用戶清單
     */
    @Query("{ 'quotaEnabled': true, 'costUsagePercent': { '$gte': ?0 } }")
    List<UserQuota> findByUsagePercentGreaterThanEqual(double percent);

    // ========== 更新操作 (@Query + @Update) ==========

    /**
     * 累加用量（單一用戶）
     */
    @Query("{ 'userId': ?0 }")
    @Update("{ '$inc': { " +
            "'periodInputTokens': ?1, 'periodOutputTokens': ?2, 'periodTokens': ?3, " +
            "'periodCostUsd': ?4, 'periodRequestCount': ?5, " +
            "'totalInputTokens': ?1, 'totalOutputTokens': ?2, 'totalTokens': ?3, " +
            "'totalEstimatedCostUsd': ?4, 'totalRequestCount': ?5 " +
            "}, '$set': { 'lastActiveAt': ?6, 'lastUpdatedAt': ?6 } }")
    long incrementUsageByUserId(String userId,
            long inputTokens, long outputTokens, long totalTokens,
            BigDecimal cost, int requestCount, Instant now);

    /**
     * 更新配額狀態
     */
    @Query("{ 'userId': ?0 }")
    @Update("{ '$set': { 'costUsagePercent': ?1, 'quotaExceeded': ?2, 'lastUpdatedAt': ?3 } }")
    long updateQuotaStatusByUserId(String userId, double usagePercent, boolean exceeded, Instant now);

    /**
     * 累加額外額度
     */
    @Query("{ 'userId': ?0 }")
    @Update("{ '$inc': { 'bonusCostUsd': ?1 }, '$set': { 'bonusReason': ?2, 'bonusGrantedAt': ?3, 'lastUpdatedAt': ?3 } }")
    long addBonusByUserId(String userId, BigDecimal amount, String reason, Instant now);

    /**
     * 重置週期並設定初始用量（用於跨月時歸檔後重置）
     */
    @Query("{ 'userId': ?0 }")
    @Update("{ '$set': { " +
            "'periodYear': ?1, 'periodMonth': ?2, 'periodStartAt': ?3, 'periodEndAt': ?4, " +
            "'periodInputTokens': ?5, 'periodOutputTokens': ?6, 'periodTokens': ?7, " +
            "'periodCostUsd': ?8, 'periodRequestCount': ?9, " +
            "'bonusCostUsd': 0, 'bonusReason': null, 'bonusGrantedAt': null, " +
            "'costUsagePercent': 0.0, 'quotaExceeded': false, " +
            "'lastActiveAt': ?10, 'lastUpdatedAt': ?10 " +
            "}, '$inc': { " +
            "'totalInputTokens': ?5, 'totalOutputTokens': ?6, 'totalTokens': ?7, " +
            "'totalEstimatedCostUsd': ?8, 'totalRequestCount': ?9 " +
            "} }")
    long resetPeriodAndSetUsageByUserId(String userId, int year, int month,
            Instant periodStart, Instant periodEnd,
            long inputTokens, long outputTokens, long totalTokens,
            BigDecimal cost, int requestCount, Instant now);

    /**
     * 設定配額上限
     */
    @Query("{ 'userId': ?0 }")
    @Update("{ '$set': { 'quotaEnabled': ?1, 'costLimitUsd': ?2, 'lastUpdatedAt': ?3 } }")
    long updateQuotaSettingsByUserId(String userId, boolean enabled, BigDecimal limitUsd, Instant now);
}
```

### 11.2 QuotaHistoryRepository

```java
public interface QuotaHistoryRepository extends MongoRepository<QuotaHistory, String> {

    // ========== 查詢方法 ==========

    /**
     * 查詢用戶所有歷史配額記錄，按年月降序排列（最新的在前）
     * @param userId 用戶唯一識別碼
     * @return 歷史記錄清單
     */
    List<QuotaHistory> findByUserIdOrderByPeriodYearDescPeriodMonthDesc(String userId);

    /**
     * 分頁查詢用戶歷史配額記錄（用於歷史記錄頁面）
     * @param userId 用戶唯一識別碼
     * @param pageable 分頁參數
     * @return 分頁結果
     */
    Page<QuotaHistory> findByUserIdOrderByPeriodYearDescPeriodMonthDesc(String userId, Pageable pageable);

    /**
     * 查詢用戶特定月份的歷史記錄
     * @param userId 用戶唯一識別碼
     * @param periodYear 年份
     * @param periodMonth 月份 (1-12)
     * @return 該月歷史記錄（如存在）
     */
    Optional<QuotaHistory> findByUserIdAndPeriodYearAndPeriodMonth(
            String userId, int periodYear, int periodMonth);

    /**
     * 檢查用戶特定月份是否已有歷史記錄（避免重複歸檔）
     * @param userId 用戶唯一識別碼
     * @param periodYear 年份
     * @param periodMonth 月份 (1-12)
     * @return true 表示該月已有歸檔記錄
     */
    boolean existsByUserIdAndPeriodYearAndPeriodMonth(
            String userId, int periodYear, int periodMonth);

    // ========== 按年份查詢 ==========

    /**
     * 查詢用戶特定年份的所有月度記錄（用於年度報表）
     * @param userId 用戶唯一識別碼
     * @param periodYear 年份
     * @return 該年各月歷史記錄，按月份降序
     */
    List<QuotaHistory> findByUserIdAndPeriodYearOrderByPeriodMonthDesc(String userId, int periodYear);

    // ========== 統計查詢 ==========

    /**
     * 統計用戶歷史記錄總數（了解用戶使用月數）
     * @param userId 用戶唯一識別碼
     * @return 歷史記錄筆數
     */
    long countByUserId(String userId);

    /**
     * 統計用戶歷史記錄總數（@Query 寫法範例）
     * @param userId 用戶唯一識別碼
     * @return 歷史記錄筆數
     */
    @Query(value = "{ 'userId': ?0 }", count = true)
    long countHistoryByUserId(String userId);
}
```

### 11.3 BonusRecordRepository

```java
public interface BonusRecordRepository extends MongoRepository<BonusRecord, String> {

    /**
     * 查詢用戶所有額外額度記錄，按建立時間降序排列（用於稽核追蹤）
     * @param userId 用戶唯一識別碼
     * @return 額外額度記錄清單
     */
    List<BonusRecord> findByUserIdOrderByCreatedAtDesc(String userId);

    /**
     * 分頁查詢用戶額外額度記錄（用於管理介面）
     * @param userId 用戶唯一識別碼
     * @param pageable 分頁參數
     * @return 分頁結果
     */
    Page<BonusRecord> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    /**
     * 查詢用戶特定月份的額外額度記錄（查看該月給予的所有額度）
     * @param userId 用戶唯一識別碼
     * @param periodYear 年份
     * @param periodMonth 月份 (1-12)
     * @return 該月額外額度記錄清單
     */
    List<BonusRecord> findByUserIdAndPeriodYearAndPeriodMonth(
            String userId, int periodYear, int periodMonth);

    // ========== 統計查詢 ==========

    /**
     * 統計用戶特定月份的額外額度給予次數
     * @param userId 用戶唯一識別碼
     * @param periodYear 年份
     * @param periodMonth 月份 (1-12)
     * @return 給予次數
     */
    long countByUserIdAndPeriodYearAndPeriodMonth(
            String userId, int periodYear, int periodMonth);
}
```

### 11.4 使用範例

```java
@Service
@RequiredArgsConstructor
public class QuotaService {

    private final UserQuotaRepository userQuotaRepository;
    private final QuotaHistoryRepository quotaHistoryRepository;

    /**
     * 累加用量後重算使用率
     */
    public void incrementUsageAndUpdateStatus(String userId,
            long inputTokens, long outputTokens, long totalTokens,
            BigDecimal cost, int requestCount) {

        Instant now = Instant.now();

        // 1. 累加用量
        userQuotaRepository.incrementUsageByUserId(userId,
            inputTokens, outputTokens, totalTokens, cost, requestCount, now);

        // 2. 重算使用率
        UserQuota quota = userQuotaRepository.findByUserId(userId).orElseThrow();
        if (quota.quotaEnabled() && quota.costLimitUsd().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal effectiveLimit = quota.costLimitUsd().add(quota.bonusCostUsd());
            double usagePercent = quota.periodCostUsd()
                .divide(effectiveLimit, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
            boolean exceeded = usagePercent >= 100;

            userQuotaRepository.updateQuotaStatusByUserId(userId, usagePercent, exceeded, now);
        }
    }

    /**
     * 給予額外額度
     */
    public void grantBonus(String userId, BigDecimal amount, String reason) {
        Instant now = Instant.now();
        userQuotaRepository.addBonusByUserId(userId, amount, reason, now);
    }
}
```

---

## 12. Implementation Plan

### Phase 1: Data Model & Repository

| Task | Description |
|------|-------------|
| 1.1 | 建立 `UserQuota` document（更新現有結構）|
| 1.2 | 建立 `QuotaHistory` document |
| 1.3 | 建立 `BonusRecord` document |
| 1.4 | 建立對應的 Repository interfaces |

### Phase 2: Core Services

| Task | Description |
|------|-------------|
| 2.1 | 更新 `UsageAggregationService.updateUserQuota()` 含週期重置邏輯 |
| 2.2 | 實作 `BonusService` |
| 2.3 | 實作週期工具方法 (`getCurrentPeriod`, `getPeriodStart`, `getPeriodEnd`) |

### Phase 3: API & UI

| Task | Description |
|------|-------------|
| 3.1 | 實作 Quota Status API |
| 3.2 | 實作 Quota Management API |
| 3.3 | 實作 Bonus API |
| 3.4 | 實作 History API |
| 3.5 | 實作儀表板 UI |

---

## 13. Summary

### Key Design Decisions

| 決策 | 說明 |
|------|------|
| **GCP Firestore** | 使用 Firestore with MongoDB compatibility 作為資料庫 |
| **Spring Data MongoDB** | 使用 Repository 介面 + @Query/@Update 註解簡化資料庫操作 |
| **ID 自動產生** | 所有 Document ID 由 MongoDB 自動產生 ObjectId |
| **避免自定義類型** | 不使用 YearMonth 等自定義類型 |
| **週期標識** | 使用 `periodYear` (int) + `periodMonth` (int)，便於查詢 |
| **統一月度週期** | 移除 `QuotaPeriod` enum，統一以月為單位管理 |
| **USD 為主** | 配額以美元設定，人類易理解 |
| **Token 同步紀錄** | 提供詳細分析數據 |
| **簡化 Bonus 流程** | 無審核，管理員直接給予 |
| **Settlement 觸發重置** | 週期重置整合於 Settlement 流程，非獨立 Cron Job |
| **歷史永久保留** | 歸檔到 `quota_history` |
| **分析為主** | 非即時卡控，適用於分析與管理場景 |
| **通知功能暫緩** | 未來擴展 |

### Collections Summary

| Collection | 說明 | ID |
|------------|------|-----|
| `user_quota` | 用戶當月配額狀態 | ObjectId (自動) |
| `quota_history` | 月度歷史記錄 | ObjectId (自動) |
| `bonus_records` | 額外額度記錄 | ObjectId (自動) |

### Data Flow Summary

```
Settlement 觸發 (每小時或手動):
  POST /settlement/trigger
       │
       ▼
  BatchSettlementService.triggerSettlement()
       │
       ▼
  UsageAggregationService.processBatch()
       │
       ├─▶ updateDailyUserUsage()
       ├─▶ updateDailyModelUsage()
       ├─▶ updateUserQuota()  ─────────────────────────────────────┐
       │       │                                                    │
       │       ├─ 用戶不存在 → 建立新 UserQuota                     │
       │       ├─ 週期不一致 → 歸檔 quota_history → 重置週期欄位   │
       │       └─ 週期一致   → 累加用量 → 重算使用率                │
       │                                                            │
       └─▶ updateSystemStats()                                      │
                                                                    ▼
                                                            user_quota 更新完成

額外額度:
  Admin API → bonus_records + 更新 user_quota.bonusCostUsd
```

---

## 14. Future Enhancements

以下功能暫不實作，留待未來擴展：

| 功能 | 說明 |
|------|------|
| **閾值通知** | 50%/80%/100% 用量警告通知 |
| **Gate Pre-check** | 請求前配額檢查 |
| **即時阻斷** | 超額時阻斷請求 |
| **審核流程** | 額外額度申請審核 |
| **分層配額** | Organization → Team → User |

---

## Appendix A: 索引管理（待討論）

> ⚠️ 本節內容為待討論議題，尚未納入實作範圍。

### A.1 背景

本專案使用 **GCP Firestore with MongoDB compatibility**，索引管理與原生 MongoDB 有所差異。

**官方文件**：https://docs.cloud.google.com/firestore/mongodb-compatibility/docs/indexing

### A.2 Firestore 索引限制

| 功能 | 支援狀態 | 說明 |
|------|----------|------|
| `createIndex` | ✅ | 支援單一索引建立 |
| `createIndexes` | ⚠️ | 官方文件有差異，建議逐一建立 |
| Compound Index | ✅ | 支援多欄位複合索引 |
| Unique Index | ✅ | 支援唯一性約束 |
| Sparse Index | ✅ | 支援稀疏索引 |
| TTL Index | ❌ | 不支援自動過期 |
| Text Index | ❌ | 不支援全文搜尋 |
| Hashed Index | ❌ | 不支援雜湊索引 |

> **重要**：Firestore with MongoDB compatibility **預設不建立任何索引**。

### A.3 Spring Data MongoDB 索引註解

| 註解 | 說明 | Firestore 支援 |
|------|------|----------------|
| `@Indexed` | 單一欄位索引 | ✅ 支援 |
| `@CompoundIndex` | 複合索引 | ✅ 支援 |
| `@HashIndexed` | 雜湊索引 | ❌ 不支援 |
| `@TextIndexed` | 全文索引 | ❌ 不支援 |
| `@GeoSpatialIndexed` | 地理空間索引 | ❌ 不支援 |

> **注意**：由於 Firestore 預設不建立索引，使用 `auto-index-creation` 可能無法如預期運作。

### A.4 待決議事項

1. **是否需要索引**：評估查詢效能需求
2. **建立方式**：gcloud CLI / MongoDB Shell / 程式碼
3. **建立時機**：部署時 / 應用啟動時
4. **索引清單**：確認需要建立哪些索引

### A.5 建議的索引（供參考）

```javascript
// UserQuota - userId 唯一索引
db.user_quota.createIndex({ "userId": 1 }, { unique: true })

// QuotaHistory - 複合唯一索引
db.quota_history.createIndex(
  { "userId": 1, "periodYear": 1, "periodMonth": 1 },
  { unique: true }
)

// BonusRecord - 複合索引
db.bonus_records.createIndex(
  { "userId": 1, "periodYear": 1, "periodMonth": 1 }
)
```

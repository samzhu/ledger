# 設計文件撰寫指南

## Document Information
- **Version**: 1.0.0
- **Created**: 2025-12-19
- **Author**: AI Assistant
- **Purpose**: 整理設計文件的格式、寫法與設計審查重點

---

## 一、文件結構重點

| 結構元素 | 說明 |
|---------|------|
| **版本控制** | 明確標示 Version、Created、Updated、Author、Status |
| **Executive Summary** | 開頭說明背景、核心原則、Scope（本期實作 vs 暫不實作） |
| **Technology Stack** | 列出使用的技術並附官方文件連結 |
| **Data Model** | 欄位說明表 + Document 定義 + 範例 JSON |
| **Data Flow** | ASCII 圖表說明流程 |
| **API Design** | Request/Response 範例 |
| **Implementation Plan** | Phase 分階段，用表格列出任務 |
| **Summary** | 重述 Key Decisions |
| **Appendix** | 待討論議題、延伸內容 |

---

## 二、寫作技巧

| 技巧 | 範例 |
|------|------|
| **表格對比** | `@Query + @Update` vs `Find + Save` 優缺點表格 |
| **正反列舉** | 「允許的類型」+「禁止的類型」明確對照 |
| **附上官方連結** | 每個技術決策都附官方文件 URL |
| **範例優先** | 每個概念都有 Java code + JSON 範例 |
| **ASCII 流程圖** | 用文字圖表說明複雜流程，不依賴外部工具 |
| **Javadoc 註解** | Repository 方法附上 `@param`、`@return` 說明意圖 |

---

## 三、設計審查重點

### 3.1 技術兼容性確認

```
❌ 假設註解/功能都能用
✅ 透過官方文件確認目標環境（如 Firestore）的支援狀態
```

**範例**：使用 GCP Firestore with MongoDB compatibility 時，需確認：
- 哪些 MongoDB 操作符受支援（`$set`, `$inc`, `$push`...）
- 哪些索引類型不支援（TTL Index, Text Index...）
- Spring Data MongoDB 註解是否如預期運作

### 3.2 程式碼風格統一

```
❌ 文件中混用 mongoTemplate + Repository
✅ 統一使用 Spring Data MongoDB Repository 模式
```

> **重點**：開發人員會照文件範例寫，所以範例要正確且一致。

### 3.3 解釋設計選擇

```
❌ 只寫 what（怎麼做）
✅ 同時寫 why（為什麼這樣做）
```

**範例**：解釋 `@Query + @Update` 的設計選擇

| 考量 | @Query + @Update | Find + Save |
|------|------------------|-------------|
| **原子性** | ✅ DB 層執行，避免併發問題 | ❌ 需樂觀鎖 `@Version` |
| **效能** | ✅ 1 次 DB 操作 | ❌ 2 次 DB 操作 |
| **$inc 支援** | ✅ 原生支援原子增量 | ❌ 需手動計算，有 race condition |

### 3.4 驗證可行性

```
❌ 寫完就好
✅ 確認在目標環境正常運作
```

**範例**：列出 Firestore 支援的 MongoDB 操作符

| 操作符 | 支援 | 說明 |
|--------|------|------|
| `$set` | ✅ | 設定欄位值 |
| `$inc` | ✅ | 原子增量 |
| `$unset` | ✅ | 移除欄位 |
| `$push` | ✅ | 陣列新增 |
| `$pull` | ✅ | 陣列移除 |

### 3.5 註解輔助理解

```
❌ findEnabledAndExceeded() // 無註解

✅ /**
    * 查詢已啟用配額且已超額的用戶（需要關注的用戶）
    * @return 已超額的用戶清單
    */
   @Query("{ 'quotaEnabled': true, 'quotaExceeded': true }")
   List<UserQuota> findEnabledAndExceeded();
```

### 3.6 待定議題分離

```
❌ 不確定的內容混在主文中
✅ 移至 Appendix 並標註「待討論」
```

**範例**：索引管理因 Firestore 限制，列在 Appendix A 並標註：

> ⚠️ 本節內容為待討論議題，尚未納入實作範圍。

---

## 四、核心原則總結

| 原則 | 說明 |
|------|------|
| **範例即規範** | 開發者會直接複製範例，確保範例正確可用 |
| **明確禁止事項** | 不只說「要做什麼」，也說「不要做什麼」 |
| **官方來源佐證** | 技術決策附官方文件連結，增加可信度 |
| **環境差異意識** | 考慮目標環境限制（如 Firestore vs MongoDB） |
| **讀者導向** | 註解、表格、圖表都是為了讓讀者快速理解 |

---

## 五、文件範本結構

```markdown
# [功能名稱] - Design Proposal

## Document Information
- **Version**: x.x.x
- **Created**: YYYY-MM-DD
- **Updated**: YYYY-MM-DD
- **Author**: [Author Name]
- **Status**: Draft / Review / Approved

---

## 1. Executive Summary

### 1.1 Background
[說明為什麼需要這個功能]

### 1.2 Core Design Principles
[列出核心設計原則，用表格呈現]

### 1.3 Scope
**本期實作**：
- [功能 1]
- [功能 2]

**暫不實作**：
- [功能 3]（未來擴展）

---

## 2. Technology Stack
[列出使用的技術並附官方文件連結]

---

## 3. Data Model

### 3.1 [Document Name]

#### 欄位說明表
| 欄位 | 類型 | 用途 | 範例值 |
|------|------|------|--------|
| ... | ... | ... | ... |

#### Document 定義
```java
// Java Record/Class 定義
```

#### 範例 Document
```json
// JSON 範例
```

---

## 4. Data Flow
[ASCII 流程圖]

---

## 5. API Design
[Request/Response 範例]

---

## 6. Implementation Plan

### Phase 1: [階段名稱]
| Task | Description |
|------|-------------|
| 1.1 | ... |

---

## 7. Summary

### Key Design Decisions
| 決策 | 說明 |
|------|------|
| ... | ... |

---

## Appendix A: [待討論議題]
> ⚠️ 本節內容為待討論議題，尚未納入實作範圍。
```

---

## 六、Checklist

在提交設計文件前，確認以下項目：

- [ ] 版本資訊完整（Version, Created, Updated, Author, Status）
- [ ] Executive Summary 包含背景、原則、範圍
- [ ] 技術選擇有官方文件連結佐證
- [ ] 已確認目標環境的兼容性
- [ ] 程式碼範例風格統一
- [ ] 設計選擇有說明 why（不只是 what）
- [ ] Repository 方法有 Javadoc 註解
- [ ] 待討論議題已移至 Appendix
- [ ] 表格、流程圖用於複雜概念說明
- [ ] 禁止事項與允許事項都有列出

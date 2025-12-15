package io.github.samzhu.ledger.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 內部用量事件 DTO，採用組合模式 (Composition Pattern)。
 *
 * <p>此 Record 組合 CloudEvent 的 metadata 和 data：
 * <ul>
 *   <li>{@code eventId} - CloudEvent id 屬性（唯一識別碼）</li>
 *   <li>{@code userId} - CloudEvent subject 屬性（用戶識別）</li>
 *   <li>{@code date} - CloudEvent time 屬性（轉換為 LocalDate）</li>
 *   <li>{@code timestamp} - CloudEvent time 屬性（原始 Instant，用於小時分析）</li>
 *   <li>{@code data} - CloudEvent data payload（{@link UsageEventData}）</li>
 * </ul>
 *
 * <p>採用組合模式的優點：
 * <ul>
 *   <li>避免欄位重複 - data 欄位定義在 {@link UsageEventData} 中</li>
 *   <li>職責分離 - CE metadata 與 CE data 清楚分開</li>
 *   <li>與 Publisher 結構對應 - {@link UsageEventData} 與 Gate 發送的格式一致</li>
 * </ul>
 *
 * <p>提供便利存取方法（delegate to data），讓既有程式碼可以直接使用
 * {@code event.model()} 而非 {@code event.data().model()}。
 *
 * @see UsageEventData
 */
public record UsageEvent(
    String eventId,
    String userId,
    LocalDate date,
    Instant timestamp,
    UsageEventData data
) {
    /**
     * 判斷此事件是否代表成功的 API 呼叫。
     *
     * @return 若 status 為 "success"（不分大小寫）則回傳 true
     */
    public boolean isSuccess() {
        return "success".equalsIgnoreCase(data.status());
    }

    // ===== 便利存取方法 (Convenience Accessors) =====
    // 委派至 data，避免呼叫端需要 event.data().xxx()

    /** @return 使用的 LLM 模型 */
    public String model() {
        return data.model();
    }

    /** @return 輸入 token 數 */
    public int inputTokens() {
        return data.inputTokens();
    }

    /** @return 輸出 token 數 */
    public int outputTokens() {
        return data.outputTokens();
    }

    /** @return Prompt Cache 建立 token 數 */
    public int cacheCreationTokens() {
        return data.cacheCreationTokens();
    }

    /** @return Prompt Cache 讀取 token 數 */
    public int cacheReadTokens() {
        return data.cacheReadTokens();
    }

    /** @return 總 token 數 */
    public int totalTokens() {
        return data.totalTokens();
    }

    /** @return API 延遲（毫秒） */
    public long latencyMs() {
        return data.latencyMs();
    }

    /** @return 是否為串流請求 */
    public boolean stream() {
        return data.stream();
    }

    /** @return 結束原因 */
    public String stopReason() {
        return data.stopReason();
    }

    /** @return 請求狀態 */
    public String status() {
        return data.status();
    }

    /** @return 錯誤類型（若有） */
    public String errorType() {
        return data.errorType();
    }

    /** @return API Key 別名 */
    public String keyAlias() {
        return data.keyAlias();
    }

    /** @return 追蹤 ID */
    public String traceId() {
        return data.traceId();
    }

    /** @return Anthropic 訊息 ID */
    public String messageId() {
        return data.messageId();
    }

    /** @return Anthropic 請求 ID */
    public String anthropicRequestId() {
        return data.anthropicRequestId();
    }
}

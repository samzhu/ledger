package io.github.samzhu.ledger.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * CloudEvents data payload，對應 Gate (LLM Gateway) 發送的用量資料。
 *
 * <p>此 Record 定義了 CloudEvent 的 {@code data} 部分結構，
 * 使用 {@code @JsonProperty} 對應 Gate 發送的 snake_case JSON 欄位名稱。
 * <b>Gate 只發布 Anthropic API 回傳的原始數據，計算邏輯由 Ledger 處理。</b>
 *
 * <h3>Token 欄位定義（依據 Anthropic API 官方文檔）</h3>
 * <p>根據 <a href="https://platform.claude.com/docs/en/build-with-claude/prompt-caching#tracking-cache-performance">
 * Anthropic Prompt Caching 文檔</a>：
 * <ul>
 *   <li>{@code inputTokens} - <b>僅包含快取斷點之後的 tokens</b>（即未被快取的輸入部分，不是總輸入！）</li>
 *   <li>{@code cacheReadTokens} - 從快取讀取的 tokens（快取命中）</li>
 *   <li>{@code cacheCreationTokens} - 寫入快取的 tokens（新建快取）</li>
 *   <li>{@code outputTokens} - 輸出 tokens</li>
 * </ul>
 *
 * <h3>Ledger 計算公式</h3>
 * <p><b>總輸入 tokens：</b>
 * <pre>
 * totalInputTokens = inputTokens + cacheCreationTokens + cacheReadTokens
 * </pre>
 *
 * <p><b>成本計算：</b>
 * <pre>
 * cost = (inputTokens × base_rate)
 *      + (cacheCreationTokens × base_rate × 1.25)
 *      + (cacheReadTokens × base_rate × 0.1)
 *      + (outputTokens × output_rate)
 * </pre>
 *
 * @see <a href="https://cloudevents.io/">CloudEvents Specification</a>
 * @see <a href="https://platform.claude.com/docs/en/api/messages/create">Claude Messages API</a>
 * @see <a href="https://platform.claude.com/docs/en/build-with-claude/prompt-caching">Prompt Caching</a>
 */
public record UsageEventData(
    // === 核心用量（原始數據）===
    String model,
    @JsonProperty("input_tokens") int inputTokens,
    @JsonProperty("output_tokens") int outputTokens,
    @JsonProperty("cache_creation_tokens") int cacheCreationTokens,
    @JsonProperty("cache_read_tokens") int cacheReadTokens,
    // === 請求資訊 ===
    @JsonProperty("message_id") String messageId,
    @JsonProperty("latency_ms") long latencyMs,
    boolean stream,
    @JsonProperty("stop_reason") String stopReason,
    // === 狀態追蹤 ===
    String status,
    @JsonProperty("error_type") String errorType,
    // === 運維資訊 ===
    @JsonProperty("key_alias") String keyAlias,
    @JsonProperty("trace_id") String traceId,
    @JsonProperty("anthropic_request_id") String anthropicRequestId
) {
}

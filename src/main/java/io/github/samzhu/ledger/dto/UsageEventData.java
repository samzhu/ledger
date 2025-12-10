package io.github.samzhu.ledger.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * CloudEvents data payload，對應 Gate (LLM Gateway) 發送的用量資料。
 *
 * <p>此 Record 定義了 CloudEvent 的 {@code data} 部分結構，
 * 使用 {@code @JsonProperty} 對應 Gate 發送的 snake_case JSON 欄位名稱。
 *
 * <p>欄位說明：
 * <ul>
 *   <li>{@code model} - 使用的 LLM 模型（例如 claude-sonnet-4-20250514）</li>
 *   <li>{@code inputTokens} - 輸入 token 數量</li>
 *   <li>{@code outputTokens} - 輸出 token 數量</li>
 *   <li>{@code cacheCreationTokens} - Prompt Cache 建立的 token 數</li>
 *   <li>{@code cacheReadTokens} - Prompt Cache 讀取的 token 數</li>
 *   <li>{@code totalTokens} - 總 token 數（input + output）</li>
 *   <li>{@code latencyMs} - API 回應延遲（毫秒）</li>
 *   <li>{@code stream} - 是否為串流請求</li>
 *   <li>{@code stopReason} - 結束原因（end_turn、max_tokens 等）</li>
 *   <li>{@code status} - 請求狀態（success 或 error）</li>
 *   <li>{@code errorType} - 錯誤類型（若 status=error）</li>
 * </ul>
 *
 * @see <a href="https://cloudevents.io/">CloudEvents Specification</a>
 * @see <a href="https://docs.anthropic.com/en/api/messages">Anthropic Messages API</a>
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
    @JsonProperty("error_type") String errorType,
    @JsonProperty("key_alias") String keyAlias,
    @JsonProperty("trace_id") String traceId,
    @JsonProperty("anthropic_request_id") String anthropicRequestId
) {
}

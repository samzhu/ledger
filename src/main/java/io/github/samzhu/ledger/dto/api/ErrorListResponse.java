package io.github.samzhu.ledger.dto.api;

import java.time.Instant;
import java.util.List;

import io.github.samzhu.ledger.service.ErrorQueryService.ErrorEvent;

/**
 * 錯誤事件列表 API 回應。
 *
 * <p>用於 GET /api/v1/errors 端點。
 */
public record ErrorListResponse(
    List<ErrorItem> errors,
    int count
) {

    /**
     * 錯誤事件項目。
     */
    public record ErrorItem(
        String batchId,
        String userId,
        Instant eventTime,
        String model,
        String status,
        String errorType,
        String traceId,
        String anthropicRequestId,
        String messageId,
        String keyAlias,
        long latencyMs,
        int inputTokens,
        int outputTokens,
        boolean stream
    ) {
        /**
         * 從 ErrorEvent 建立 ErrorItem。
         */
        public static ErrorItem from(ErrorEvent event) {
            return new ErrorItem(
                event.batchId(),
                event.userId(),
                event.eventTime(),
                event.model(),
                event.status(),
                event.errorType(),
                event.traceId(),
                event.anthropicRequestId(),
                event.messageId(),
                event.keyAlias(),
                event.latencyMs(),
                event.inputTokens(),
                event.outputTokens(),
                event.stream()
            );
        }
    }

    /**
     * 從 ErrorEvent 列表建立回應物件。
     */
    public static ErrorListResponse fromErrorEvents(List<ErrorEvent> events) {
        List<ErrorItem> items = events.stream()
            .map(ErrorItem::from)
            .toList();
        return new ErrorListResponse(items, items.size());
    }
}

package io.github.samzhu.ledger.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import io.github.samzhu.ledger.document.RawEventBatch;
import io.github.samzhu.ledger.dto.UsageEventData;

/**
 * 錯誤事件查詢服務。
 *
 * <p>從 RawEventBatch 文件中提取錯誤事件，提供給錯誤監控頁面使用。
 */
@Service
public class ErrorQueryService {

    private static final Logger log = LoggerFactory.getLogger(ErrorQueryService.class);

    private final MongoTemplate mongoTemplate;

    public ErrorQueryService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * 取得最近的錯誤事件。
     *
     * <p>從最近的 RawEventBatch 批次中提取錯誤事件，依時間倒序排列。
     *
     * @param limit 最大回傳數量
     * @return 錯誤事件列表
     */
    public List<ErrorEvent> getRecentErrors(int limit) {
        log.debug("Querying recent error events, limit={}", limit);

        // 查詢最近的批次（多查一些以確保有足夠的錯誤事件）
        Query query = new Query()
            .with(Sort.by(Sort.Direction.DESC, "createdAt"))
            .with(PageRequest.of(0, 100));

        List<RawEventBatch> batches = mongoTemplate.find(query, RawEventBatch.class);

        // 從批次中提取錯誤事件
        List<ErrorEvent> errorEvents = new ArrayList<>();
        for (RawEventBatch batch : batches) {
            if (batch.events() == null) continue;

            for (UsageEventData event : batch.events()) {
                if (!event.isSuccess()) {
                    errorEvents.add(ErrorEvent.from(event, batch.id()));

                    // 提前退出以提高效能
                    if (errorEvents.size() >= limit * 2) break;
                }
            }
            if (errorEvents.size() >= limit * 2) break;
        }

        // 依時間倒序排序並限制數量
        errorEvents.sort(Comparator.comparing(ErrorEvent::eventTime).reversed());

        List<ErrorEvent> result = errorEvents.size() > limit
            ? errorEvents.subList(0, limit)
            : errorEvents;

        log.debug("Found {} error events", result.size());
        return result;
    }

    /**
     * 錯誤事件資料。
     */
    public record ErrorEvent(
        String batchId,
        String userId,
        java.time.Instant eventTime,
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
         * 從 UsageEventData 建立 ErrorEvent。
         */
        public static ErrorEvent from(UsageEventData event, String batchId) {
            return new ErrorEvent(
                batchId,
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
}

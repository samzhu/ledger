package io.github.samzhu.ledger.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.github.samzhu.ledger.config.LedgerProperties;
import io.github.samzhu.ledger.document.RawEventBatch;
import io.github.samzhu.ledger.dto.UsageEventData;
import io.github.samzhu.ledger.repository.RawEventBatchRepository;

/**
 * 事件緩衝服務，負責批次儲存用量事件到 MongoDB。
 *
 * <p>此服務在記憶體中緩衝接收到的事件，並在以下條件觸發寫入：
 * <ul>
 *   <li>緩衝區事件數達到 {@code batchSize}（由 {@code ledger.batch.size} 配置）</li>
 *   <li>Cron 定時觸發（由 {@code ledger.batch.flush-cron} 配置，預設每小時 00 分和 30 分）</li>
 *   <li>應用程式關閉時（graceful shutdown）</li>
 * </ul>
 *
 * <p>寫入流程：
 * <ol>
 *   <li>將事件批次儲存為 {@link RawEventBatch}（processed=false）</li>
 *   <li>聚合統計由 {@link BatchSettlementService} 定時執行（每小時整點）</li>
 *   <li>若寫入失敗，事件會重新加入緩衝區等待下次 retry</li>
 * </ol>
 *
 * <p>實作 {@link SmartLifecycle} 確保：
 * <ul>
 *   <li>關閉時 flush 所有緩衝事件到資料庫</li>
 *   <li>關閉順序在 Spring Cloud Stream bindings 之後（phase: MAX_VALUE - 100）</li>
 * </ul>
 *
 * @see BatchSettlementService
 * @see <a href="https://docs.spring.io/spring-framework/reference/core/beans/factory-nature.html#beans-factory-lifecycle-processor">SmartLifecycle</a>
 */
@Service
public class EventBufferService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EventBufferService.class);

    private final RawEventBatchRepository rawEventBatchRepository;
    private final List<UsageEventData> eventBuffer = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final int batchSize;

    public EventBufferService(
            RawEventBatchRepository rawEventBatchRepository,
            LedgerProperties properties) {
        this.rawEventBatchRepository = rawEventBatchRepository;
        this.batchSize = properties.batch().size();
    }

    /**
     * 將事件加入緩衝區。
     *
     * <p>若緩衝區大小達到 {@code batchSize}，立即觸發 flush。
     *
     * @param event 用量事件
     */
    public void addEvent(UsageEventData event) {
        eventBuffer.add(event);
        log.debug("Event buffered: userId={}, model={}, bufferSize={}",
            event.userId(), event.model(), eventBuffer.size());

        if (eventBuffer.size() >= batchSize) {
            log.info("Buffer size reached {}, triggering flush", batchSize);
            flushBuffer();
        }
    }

    /**
     * 將緩衝區的所有事件寫入資料庫。
     *
     * <p>此方法為 synchronized，確保同時只有一個執行緒執行 flush。
     * 若寫入失敗，事件會重新加入緩衝區等待下次 retry。
     */
    public synchronized void flushBuffer() {
        if (eventBuffer.isEmpty()) {
            log.debug("Buffer is empty, nothing to flush");
            return;
        }

        List<UsageEventData> batch = new ArrayList<>(eventBuffer);
        eventBuffer.clear();

        log.info("Flushing {} events to database", batch.size());
        long startTime = System.currentTimeMillis();

        try {
            // 儲存原始事件批次（processed=false，等待結算服務處理）
            RawEventBatch rawBatch = RawEventBatch.create(batch);
            rawEventBatchRepository.save(rawBatch);

            long duration = System.currentTimeMillis() - startTime;
            log.info("Flush completed: id={}, {} events in {}ms", rawBatch.id(), batch.size(), duration);
        } catch (Exception e) {
            log.error("Failed to flush {} events, re-adding to buffer for retry: {}",
                batch.size(), e.getMessage(), e);
            eventBuffer.addAll(0, batch);
        }
    }

    /**
     * 定時觸發 flush，使用 Cron 表達式（預設每小時 00 分和 30 分）。
     *
     * <p>只在服務 running 狀態時執行，避免啟動或關閉過程中執行。
     */
    @Scheduled(cron = "${ledger.batch.flush-cron:0 0,30 * * * *}")
    public void scheduledFlush() {
        if (running.get()) {
            log.debug("Scheduled flush triggered, buffer size: {}", eventBuffer.size());
            flushBuffer();
        } else {
            log.debug("Scheduled flush skipped: service not running");
        }
    }

    // ===== SmartLifecycle Implementation =====

    @Override
    public void start() {
        running.set(true);
        log.info("EventBufferService started: batchSize={}", batchSize);
    }

    @Override
    public void stop() {
        log.info("EventBufferService stopping, flushing remaining {} events...", eventBuffer.size());
        running.set(false);
        flushBuffer();
        log.info("EventBufferService stopped");
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
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    /**
     * 取得目前緩衝區大小，用於監控。
     *
     * @return 緩衝區中的事件數量
     */
    public int getBufferSize() {
        return eventBuffer.size();
    }
}

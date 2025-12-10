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
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import io.github.samzhu.ledger.config.LedgerProperties;
import io.github.samzhu.ledger.document.RawEventBatch;
import io.github.samzhu.ledger.dto.UsageEvent;
import io.github.samzhu.ledger.repository.RawEventBatchRepository;

/**
 * 事件緩衝服務，負責批次處理用量事件。
 *
 * <p>此服務在記憶體中緩衝接收到的事件，並在以下條件觸發寫入：
 * <ul>
 *   <li>緩衝區事件數達到 {@code batchSize}（預設 100）</li>
 *   <li>定時器每隔 {@code intervalMs}（預設 5000ms）觸發</li>
 *   <li>應用程式關閉時（graceful shutdown）</li>
 * </ul>
 *
 * <p>寫入流程：
 * <ol>
 *   <li>將事件批次儲存為 {@link RawEventBatch}（成本優化）</li>
 *   <li>呼叫 {@link UsageAggregationService} 更新統計文件</li>
 *   <li>若寫入失敗，事件會重新加入緩衝區（retry）</li>
 * </ol>
 *
 * <p>實作 {@link SmartLifecycle} 確保：
 * <ul>
 *   <li>應用程式啟動時自動啟動定時器</li>
 *   <li>關閉時先停止接收，再 flush 所有緩衝事件</li>
 *   <li>關閉順序在 Spring Cloud Stream bindings 之後</li>
 * </ul>
 *
 * @see <a href="https://docs.spring.io/spring-framework/reference/core/beans/factory-nature.html#beans-factory-lifecycle-processor">SmartLifecycle</a>
 */
@Service
public class EventBufferService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EventBufferService.class);

    private final RawEventBatchRepository rawEventBatchRepository;
    private final UsageAggregationService aggregationService;
    private final List<UsageEvent> eventBuffer = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final int batchSize;
    private final long flushIntervalMs;

    public EventBufferService(
            RawEventBatchRepository rawEventBatchRepository,
            UsageAggregationService aggregationService,
            LedgerProperties properties) {
        this.rawEventBatchRepository = rawEventBatchRepository;
        this.aggregationService = aggregationService;
        this.batchSize = properties.batch().size();
        this.flushIntervalMs = properties.batch().intervalMs();
    }

    /**
     * 將事件加入緩衝區。
     *
     * <p>若緩衝區大小達到 {@code batchSize}，立即觸發 flush。
     *
     * @param event 用量事件
     */
    public void addEvent(UsageEvent event) {
        eventBuffer.add(event);
        log.debug("Event buffered: eventId={}, userId={}, bufferSize={}",
            event.eventId(), event.userId(), eventBuffer.size());

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

        List<UsageEvent> batch = new ArrayList<>(eventBuffer);
        eventBuffer.clear();

        log.info("Flushing {} events to database", batch.size());
        long startTime = System.currentTimeMillis();

        try {
            // 1. 儲存原始事件批次（成本優化：100 事件 = 1 document）
            RawEventBatch rawBatch = RawEventBatch.create(batch);
            rawEventBatchRepository.save(rawBatch);
            log.info("Raw event batch saved: id={}, eventCount={}", rawBatch.id(), rawBatch.eventCount());

            // 2. 更新聚合統計
            aggregationService.processBatch(batch);

            long duration = System.currentTimeMillis() - startTime;
            log.info("Flush completed: {} events in {}ms", batch.size(), duration);
        } catch (Exception e) {
            log.error("Failed to flush {} events, re-adding to buffer for retry: {}",
                batch.size(), e.getMessage(), e);
            eventBuffer.addAll(0, batch);
        }
    }

    // ===== SmartLifecycle Implementation =====

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(
                this::flushBuffer,
                flushIntervalMs,
                flushIntervalMs,
                TimeUnit.MILLISECONDS
            );
            log.info("EventBufferService started: batchSize={}, flushIntervalMs={}", batchSize, flushIntervalMs);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("EventBufferService stopping, flushing remaining {} events...", eventBuffer.size());

            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("Scheduler did not terminate within 10 seconds");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 確保所有事件都寫入資料庫
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
        // 在 Spring Cloud Stream bindings 之後關閉
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
     * 取得目前緩衝區大小，用於監控。
     *
     * @return 緩衝區中的事件數量
     */
    public int getBufferSize() {
        return eventBuffer.size();
    }
}

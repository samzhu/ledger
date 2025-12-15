package io.github.samzhu.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Ledger Service - LLM API 用量統計與成本追蹤服務。
 *
 * <p>此服務作為 LLM Gateway (Gate) 的下游消費者，負責：
 * <ul>
 *   <li>接收 CloudEvents 格式的 API 用量事件</li>
 *   <li>批次儲存原始事件資料 (成本優化)</li>
 *   <li>即時聚合統計 (按用戶/模型/日期)</li>
 *   <li>計算 API 使用成本 (基於 token 定價)</li>
 *   <li>提供 REST API 查詢用量報表</li>
 * </ul>
 *
 * <p>架構流程：
 * <pre>
 * Gate (Publisher) → Pub/Sub → Ledger Consumer → MongoDB
 *                                    ↓
 *                              raw_event_batches (原始事件)
 *                              daily_user_usage  (用戶日統計)
 *                              daily_model_usage (模型日統計)
 *                              user_summary      (用戶累計)
 *                              system_stats      (系統日統計)
 * </pre>
 *
 * @see <a href="https://cloudevents.io/">CloudEvents Specification</a>
 * @see <a href="https://docs.spring.io/spring-cloud-stream/reference/">Spring Cloud Stream</a>
 */
@SpringBootApplication
@EnableScheduling
public class LedgerApplication {

    private static final Logger log = LoggerFactory.getLogger(LedgerApplication.class);

    public static void main(String[] args) {
        log.info("Starting Ledger Service - LLM Usage Analytics");
        SpringApplication.run(LedgerApplication.class, args);
    }
}

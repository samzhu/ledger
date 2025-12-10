package io.github.samzhu.ledger.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * MongoDB 資料庫配置。
 *
 * <p>啟用以下功能：
 * <ul>
 *   <li>Repository 自動掃描 - 自動註冊 {@code io.github.samzhu.ledger.repository} 下的介面</li>
 *   <li>Auditing 審計功能 - 支援 {@code @CreatedDate}、{@code @LastModifiedDate} 等註解</li>
 * </ul>
 *
 * <p>資料庫集合 (Collections)：
 * <ul>
 *   <li>{@code raw_event_batches} - 批次原始事件儲存</li>
 *   <li>{@code daily_user_usage} - 用戶日用量聚合</li>
 *   <li>{@code daily_model_usage} - 模型日用量聚合</li>
 *   <li>{@code user_summary} - 用戶累計統計</li>
 *   <li>{@code system_stats} - 系統日統計</li>
 * </ul>
 *
 * @see <a href="https://docs.spring.io/spring-data/mongodb/reference/mongodb/configuration.html">Spring Data MongoDB Configuration</a>
 */
@Configuration
@EnableMongoRepositories(basePackages = "io.github.samzhu.ledger.repository")
@EnableMongoAuditing
public class MongoConfig {
}

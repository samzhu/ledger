package io.github.samzhu.ledger.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

import io.github.samzhu.ledger.document.DailyModelUsage;
import io.github.samzhu.ledger.document.DailyUserUsage;
import io.github.samzhu.ledger.document.RawEventBatch;
import io.github.samzhu.ledger.document.SystemStats;
import io.github.samzhu.ledger.document.UserQuota;
import io.github.samzhu.ledger.dto.UsageEventData;
import io.github.samzhu.ledger.controller.UsageApiController;
import io.github.samzhu.ledger.service.UsageQueryService;

/**
 * GraalVM Native Image 執行時期提示配置。
 *
 * <p>GraalVM Native Image 在編譯時期進行靜態分析，無法自動偵測
 * 執行時期的反射呼叫。此配置註冊需要反射存取的類別，確保
 * Native Image 編譯時包含必要的 metadata。
 *
 * <p>需要註冊的類別（用於 Jackson JSON 序列化/反序列化）：
 * <ul>
 *   <li>{@link UsageEventData} - 用量事件資料，從 Gate 接收，包含 userId 和 eventTime</li>
 *   <li>{@link RawEventBatch} - 批次原始事件文件，儲存至 MongoDB</li>
 * </ul>
 *
 * <p>使用 {@link MemberCategory#values()} 註冊所有成員類別，
 * 包括 constructors、fields、methods 等，確保 Jackson 能完整操作這些類別。
 *
 * @see <a href="https://docs.spring.io/spring-boot/reference/native-image/introducing-graalvm-native-images.html">Spring Boot Native Image Support</a>
 * @see <a href="https://www.graalvm.org/latest/reference-manual/native-image/metadata/">GraalVM Reachability Metadata</a>
 */
@Configuration
@ImportRuntimeHints(NativeHintsConfig.LedgerRuntimeHints.class)
public class NativeHintsConfig {

    /**
     * RuntimeHintsRegistrar 實作，註冊 Ledger 服務所需的反射提示。
     */
    static class LedgerRuntimeHints implements RuntimeHintsRegistrar {

        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            // 註冊 DTO 類別供 Jackson 反射使用
            hints.reflection()
                .registerType(UsageEventData.class, MemberCategory.values())
                .registerType(RawEventBatch.class, MemberCategory.values());

            // 註冊 Document 類別供 Thymeleaf 模板反射使用
            hints.reflection()
                // DailyUserUsage 及其嵌套記錄
                .registerType(DailyUserUsage.class, MemberCategory.values())
                .registerType(DailyUserUsage.LatencyStats.class, MemberCategory.values())
                .registerType(DailyUserUsage.CacheEfficiency.class, MemberCategory.values())
                .registerType(DailyUserUsage.HourlyBreakdown.class, MemberCategory.values())
                .registerType(DailyUserUsage.ModelBreakdown.class, MemberCategory.values())
                .registerType(DailyUserUsage.CostBreakdown.class, MemberCategory.values())
                // DailyModelUsage 及其嵌套記錄
                .registerType(DailyModelUsage.class, MemberCategory.values())
                .registerType(DailyModelUsage.LatencyStats.class, MemberCategory.values())
                .registerType(DailyModelUsage.CacheEfficiency.class, MemberCategory.values())
                // SystemStats 及其嵌套記錄
                .registerType(SystemStats.class, MemberCategory.values())
                .registerType(SystemStats.TopItem.class, MemberCategory.values())
                // UserQuota 及其嵌套記錄
                .registerType(UserQuota.class, MemberCategory.values())
                .registerType(UserQuota.Builder.class, MemberCategory.values())
                // UsageQueryService 內部記錄
                .registerType(UsageQueryService.ModelSummary.class, MemberCategory.values())
                // UsageApiController 內部記錄
                .registerType(UsageApiController.FlushResult.class, MemberCategory.values())
                .registerType(UsageApiController.SettlementResult.class, MemberCategory.values())
                .registerType(UsageApiController.ProcessResult.class, MemberCategory.values());

            // 註冊 SpEL 表達式中使用的 JDK 類別（用於 Thymeleaf 模板的 T() 運算符）
            hints.reflection()
                .registerType(java.lang.Math.class, MemberCategory.values())
                .registerType(java.math.BigDecimal.class, MemberCategory.values());

            // 註冊 Quota API DTO 類別供 Jackson 反射使用
            hints.reflection()
                .registerType(io.github.samzhu.ledger.dto.api.QuotaStatusResponse.class, MemberCategory.values())
                .registerType(io.github.samzhu.ledger.dto.api.QuotaConfigRequest.class, MemberCategory.values())
                .registerType(io.github.samzhu.ledger.dto.api.BonusGrantRequest.class, MemberCategory.values())
                .registerType(io.github.samzhu.ledger.dto.api.QuotaHistoryResponse.class, MemberCategory.values())
                .registerType(io.github.samzhu.ledger.dto.api.QuotaHistoryResponse.HistoryItem.class, MemberCategory.values())
                .registerType(io.github.samzhu.ledger.dto.api.QuotaHistoryResponse.UsageDetail.class, MemberCategory.values())
                .registerType(io.github.samzhu.ledger.dto.api.QuotaHistoryResponse.QuotaDetail.class, MemberCategory.values())
                .registerType(io.github.samzhu.ledger.dto.api.QuotaHistoryResponse.ModelDetail.class, MemberCategory.values())
                .registerType(io.github.samzhu.ledger.dto.api.BonusHistoryResponse.class, MemberCategory.values())
                .registerType(io.github.samzhu.ledger.dto.api.BonusHistoryResponse.BonusItem.class, MemberCategory.values())
                // BonusRecord 和 QuotaHistory document
                .registerType(io.github.samzhu.ledger.document.BonusRecord.class, MemberCategory.values())
                .registerType(io.github.samzhu.ledger.document.QuotaHistory.class, MemberCategory.values());
        }
    }
}

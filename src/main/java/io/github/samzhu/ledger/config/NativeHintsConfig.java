package io.github.samzhu.ledger.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

import io.github.samzhu.ledger.document.RawEventBatch;
import io.github.samzhu.ledger.dto.UsageEvent;
import io.github.samzhu.ledger.dto.UsageEventData;

/**
 * GraalVM Native Image 執行時期提示配置。
 *
 * <p>GraalVM Native Image 在編譯時期進行靜態分析，無法自動偵測
 * 執行時期的反射呼叫。此配置註冊需要反射存取的類別，確保
 * Native Image 編譯時包含必要的 metadata。
 *
 * <p>需要註冊的類別（用於 Jackson JSON 序列化/反序列化）：
 * <ul>
 *   <li>{@link UsageEventData} - CloudEvents data payload，從 Gate 接收</li>
 *   <li>{@link UsageEvent} - 內部用量事件 DTO，組合 CE headers + data</li>
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
                .registerType(UsageEvent.class, MemberCategory.values())
                .registerType(RawEventBatch.class, MemberCategory.values());
        }
    }
}

package io.github.samzhu.ledger.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 應用程式主要配置類別。
 *
 * <p>啟用 {@link LedgerProperties} 的型別安全配置綁定，
 * 使服務可以透過 constructor injection 取得配置值。
 *
 * @see LedgerProperties
 * @see <a href="https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.enabling-annotated-types">Enabling @ConfigurationProperties</a>
 */
@Configuration
@EnableConfigurationProperties(LedgerProperties.class)
public class AppConfig {
}

package io.github.samzhu.ledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.cloudevents.spring.messaging.CloudEventMessageConverter;

/**
 * CloudEvents 訊息轉換器配置。
 *
 * <p>Gate (上游服務) 以 <b>Structured Mode</b> 發送 CloudEvents，
 * 訊息格式為 {@code application/cloudevents+json}，整個 CloudEvent
 * (包含 attributes 和 data) 封裝在 JSON body 中。
 *
 * <p>此配置註冊 {@link CloudEventMessageConverter}，使 Spring Cloud Stream
 * 能夠解析 Structured Mode 訊息，並將其轉換為：
 * <ul>
 *   <li>CloudEvent attributes (id, type, source, subject, time) → Message Headers</li>
 *   <li>CloudEvent data → Message Payload (自動反序列化為 POJO)</li>
 * </ul>
 *
 * <p>轉換後，消費者函式即可使用 {@code CloudEventMessageUtils} 從 headers 讀取
 * CloudEvent 屬性，同時透過 {@code message.getPayload()} 取得型別化的資料物件。
 *
 * @see <a href="https://cloudevents.io/">CloudEvents Specification</a>
 * @see <a href="https://cloudevents.github.io/sdk-java/spring.html">CloudEvents Java SDK - Spring Integration</a>
 */
@Configuration
public class CloudEventsConfig {

    /**
     * 註冊 CloudEvents 訊息轉換器。
     *
     * <p>此轉換器會自動加入 Spring 的 MessageConverter 鏈，
     * 處理 CloudEvent 物件的序列化/反序列化。
     *
     * <p>需要 {@code cloudevents-json-jackson} 依賴，
     * 該依賴透過 Java ServiceLoader 機制提供 JSON 序列化支援。
     *
     * @return CloudEventMessageConverter 實例
     */
    @Bean
    public CloudEventMessageConverter cloudEventMessageConverter() {
        return new CloudEventMessageConverter();
    }
}

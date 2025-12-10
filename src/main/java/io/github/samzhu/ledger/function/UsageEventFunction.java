package io.github.samzhu.ledger.function;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.function.cloudevent.CloudEventMessageUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import io.github.samzhu.ledger.dto.UsageEvent;
import io.github.samzhu.ledger.dto.UsageEventData;
import io.github.samzhu.ledger.service.EventBufferService;

/**
 * CloudEvents 消費者函式配置。
 *
 * <p>使用 Spring Cloud Function 程式設計模型，消費來自訊息代理的 CloudEvents：
 * <ul>
 *   <li>本地開發：RabbitMQ</li>
 *   <li>GCP 部署：Pub/Sub</li>
 * </ul>
 *
 * <p>Gate (Publisher) 以 <b>Structured Mode</b> ({@code application/cloudevents+json})
 * 發送事件，Spring Cloud Stream 自動解析後：
 * <ul>
 *   <li>CloudEvent attributes → Message Headers</li>
 *   <li>CloudEvent data → Message Payload（自動轉換為 POJO）</li>
 * </ul>
 *
 * <p>此實作使用 {@link CloudEventMessageUtils} 從 headers 提取 CE attributes，
 * 不論原始格式是 Structured 或 Binary Mode 都能正確處理。
 *
 * <p>Binding name: {@code usageEventConsumer-in-0}
 *
 * @see <a href="https://spring.io/blog/2020/12/23/cloud-events-and-spring-part-2/">Cloud Events and Spring - part 2</a>
 * @see <a href="https://docs.spring.io/spring-cloud-stream/reference/spring-cloud-stream/producing-and-consuming-messages.html">Spring Cloud Stream Function Model</a>
 */
@Configuration
public class UsageEventFunction {

    private static final Logger log = LoggerFactory.getLogger(UsageEventFunction.class);

    private final EventBufferService bufferService;

    public UsageEventFunction(EventBufferService bufferService) {
        this.bufferService = bufferService;
    }

    /**
     * CloudEvents 用量事件消費者 Bean。
     *
     * <p>Spring Cloud Stream 會將此 Bean 綁定到 {@code usageEventConsumer-in-0}。
     * 訊息的 CloudEvent attributes 在 headers 中，payload 自動轉換為 {@link UsageEventData}。
     *
     * <p>處理流程：
     * <ol>
     *   <li>從 headers 提取 CE attributes (id, subject, time)</li>
     *   <li>取得自動轉換的 payload</li>
     *   <li>組合成 {@link UsageEvent} 並加入緩衝區</li>
     * </ol>
     *
     * <p>錯誤處理：不重新拋出例外，避免訊息重複投遞迴圈。
     *
     * @return CloudEvents 訊息消費者
     */
    @Bean
    public Consumer<Message<UsageEventData>> usageEventConsumer() {
        return message -> {
            try {
                // 從 headers 提取 CloudEvent attributes
                String eventId = CloudEventMessageUtils.getId(message);
                String subject = CloudEventMessageUtils.getSubject(message);
                OffsetDateTime time = CloudEventMessageUtils.getTime(message);

                log.debug("CloudEvent received: id={}, type={}, source={}, subject={}",
                    eventId,
                    CloudEventMessageUtils.getType(message),
                    CloudEventMessageUtils.getSource(message),
                    subject);

                // Payload 由 Spring 自動轉換為 UsageEventData
                UsageEventData data = message.getPayload();

                UsageEvent event = new UsageEvent(
                    eventId,
                    subject,  // userId 在 CE subject 中
                    time != null ? time.toLocalDate() : LocalDate.now(),
                    data
                );

                bufferService.addEvent(event);

                log.info("Event consumed: eventId={}, userId={}, model={}, tokens={}",
                    eventId, subject, data.model(), data.totalTokens());
            } catch (Exception e) {
                log.error("Failed to process CloudEvent: {}", e.getMessage(), e);
                // 不重新拋出例外，避免訊息重複投遞
            }
        };
    }
}

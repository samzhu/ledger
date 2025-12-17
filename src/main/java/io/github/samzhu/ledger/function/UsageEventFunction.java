package io.github.samzhu.ledger.function;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.function.cloudevent.CloudEventMessageUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

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
 * <p>Gate 已將 {@code userId} 和 {@code eventTime} 包含在 data payload 中，
 * 因此 Ledger 不再需要從 CloudEvent headers 提取這些資訊。
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
     * Payload 自動轉換為 {@link UsageEventData}，其中包含 {@code userId} 和 {@code eventTime}。
     *
     * <p>處理流程：
     * <ol>
     *   <li>取得自動轉換的 payload（{@link UsageEventData}）</li>
     *   <li>將事件加入緩衝區</li>
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
                // Payload 由 Spring 自動轉換為 UsageEventData
                UsageEventData data = message.getPayload();

                log.debug("CloudEvent received: id={}, type={}, source={}, userId={}",
                    CloudEventMessageUtils.getId(message),
                    CloudEventMessageUtils.getType(message),
                    CloudEventMessageUtils.getSource(message),
                    data.userId());

                bufferService.addEvent(data);

                log.debug("Event consumed: userId={}, model={}, tokens={}",
                    data.userId(), data.model(), data.totalTokens());
            } catch (Exception e) {
                log.error("Failed to process CloudEvent: id={}, error={}",
                    CloudEventMessageUtils.getId(message), e.getMessage(), e);
                // 不重新拋出例外，避免訊息重複投遞
            }
        };
    }
}

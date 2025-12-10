package io.github.samzhu.ledger.function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.function.cloudevent.CloudEventMessageUtils;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MimeTypeUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.samzhu.ledger.dto.UsageEvent;
import io.github.samzhu.ledger.dto.UsageEventData;
import io.github.samzhu.ledger.service.EventBufferService;

/**
 * Integration test for UsageEventFunction using Spring Cloud Stream Test Binder.
 *
 * <p>The publisher sends CloudEvents in <b>Structured Mode</b> (application/cloudevents+json),
 * but Spring Cloud Stream automatically parses the CloudEvent and presents it to the consumer
 * with CloudEvent attributes in message headers and the data payload in the message body.
 *
 * <p>This test simulates the message format that the consumer function receives after
 * Spring Cloud Stream has parsed the incoming CloudEvent - with attributes in headers
 * and only the data field in the payload.
 *
 * @see <a href="https://docs.spring.io/spring-cloud-stream/reference/spring-cloud-stream/spring_integration_test_binder.html">Test Binder</a>
 * @see <a href="https://cloudevents.io/">CloudEvents Specification</a>
 */
class UsageEventFunctionTest {

    private static ConfigurableApplicationContext context;
    private static InputDestination inputDestination;
    private static EventBufferService mockBufferService;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void setupContext() {
        mockBufferService = mock(EventBufferService.class);

        context = new SpringApplicationBuilder(
            TestChannelBinderConfiguration.getCompleteConfiguration(TestConfig.class))
            .web(WebApplicationType.NONE)
            .run(
                "--spring.cloud.function.definition=usageEventConsumer",
                "--spring.jmx.enabled=false",
                "--spring.autoconfigure.exclude=com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubAutoConfiguration,com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubReactiveAutoConfiguration,com.google.cloud.spring.autoconfigure.pubsub.stream.GcpPubSubBinderAutoConfiguration,com.google.cloud.spring.autoconfigure.core.GcpContextAutoConfiguration"
            );

        inputDestination = context.getBean(InputDestination.class);
        objectMapper = context.getBean(ObjectMapper.class);
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void resetMock() {
        reset(mockBufferService);
    }

    @Test
    void shouldProcessCloudEventMessage() throws Exception {
        // Given: CloudEvent data payload (as received after Spring Cloud Stream parses Structured Mode)
        UsageEventData eventData = new UsageEventData(
            "claude-sonnet-4-20250514",
            "msg-123",
            1000,
            500,
            100,
            200,
            1800,
            1500L,
            true,
            "end_turn",
            "success",
            null,  // errorType
            "primary-key",
            "trace-456",
            "anthro-req-789"
        );

        String eventId = UUID.randomUUID().toString();
        String userId = "user-abc-123";
        OffsetDateTime eventTime = OffsetDateTime.now();
        URI source = URI.create("https://gate.example.com/api");
        String eventType = "io.github.samzhu.gate.usage.v1";

        // Payload is the data field (Spring Cloud Stream extracts data from Structured Mode)
        byte[] payload = objectMapper.writeValueAsBytes(eventData);

        // CloudEvent attributes are in headers (Spring Cloud Stream parses Structured Mode)
        Message<byte[]> message = MessageBuilder.withPayload(payload)
            .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON)
            .setHeader(CloudEventMessageUtils.ID, eventId)
            .setHeader(CloudEventMessageUtils.SOURCE, source)
            .setHeader(CloudEventMessageUtils.TYPE, eventType)
            .setHeader(CloudEventMessageUtils.SUBJECT, userId)
            .setHeader(CloudEventMessageUtils.TIME, eventTime)
            .setHeader(CloudEventMessageUtils.SPECVERSION, "1.0")
            .build();

        // When: Send message to input destination
        inputDestination.send(message);

        // Then: Verify EventBufferService received the event
        ArgumentCaptor<UsageEvent> eventCaptor = ArgumentCaptor.forClass(UsageEvent.class);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            verify(mockBufferService, atLeastOnce()).addEvent(eventCaptor.capture());
        });

        UsageEvent capturedEvent = eventCaptor.getValue();

        assertThat(capturedEvent.eventId()).isEqualTo(eventId);
        assertThat(capturedEvent.userId()).isEqualTo(userId);
        assertThat(capturedEvent.model()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(capturedEvent.inputTokens()).isEqualTo(1000);
        assertThat(capturedEvent.outputTokens()).isEqualTo(500);
        assertThat(capturedEvent.cacheCreationTokens()).isEqualTo(100);
        assertThat(capturedEvent.cacheReadTokens()).isEqualTo(200);
        assertThat(capturedEvent.totalTokens()).isEqualTo(1800);
        assertThat(capturedEvent.latencyMs()).isEqualTo(1500L);
        assertThat(capturedEvent.stream()).isTrue();
        assertThat(capturedEvent.stopReason()).isEqualTo("end_turn");
        assertThat(capturedEvent.status()).isEqualTo("success");
        assertThat(capturedEvent.keyAlias()).isEqualTo("primary-key");
        assertThat(capturedEvent.traceId()).isEqualTo("trace-456");
        assertThat(capturedEvent.messageId()).isEqualTo("msg-123");
        assertThat(capturedEvent.anthropicRequestId()).isEqualTo("anthro-req-789");
        assertThat(capturedEvent.date()).isEqualTo(eventTime.toLocalDate());
    }

    @Test
    void shouldHandleMessageWithoutTime() throws Exception {
        // Given: CloudEvent without time header
        UsageEventData eventData = new UsageEventData(
            "claude-haiku-3-5-20241022",
            "msg-456",
            500,
            250,
            0,
            0,
            750,
            800L,
            false,
            "max_tokens",
            "success",
            null,  // errorType
            "secondary-key",
            "trace-789",
            "anthro-req-abc"
        );

        String eventId = UUID.randomUUID().toString();
        String userId = "user-xyz-789";
        byte[] payload = objectMapper.writeValueAsBytes(eventData);

        Message<byte[]> message = MessageBuilder.withPayload(payload)
            .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON)
            .setHeader(CloudEventMessageUtils.ID, eventId)
            .setHeader(CloudEventMessageUtils.SOURCE, URI.create("https://gate.example.com"))
            .setHeader(CloudEventMessageUtils.TYPE, "io.github.samzhu.gate.usage.v1")
            .setHeader(CloudEventMessageUtils.SUBJECT, userId)
            .setHeader(CloudEventMessageUtils.SPECVERSION, "1.0")
            // No TIME header
            .build();

        // When
        inputDestination.send(message);

        // Then: Should use LocalDate.now() as fallback
        ArgumentCaptor<UsageEvent> eventCaptor = ArgumentCaptor.forClass(UsageEvent.class);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            verify(mockBufferService, atLeastOnce()).addEvent(eventCaptor.capture());
        });

        UsageEvent capturedEvent = eventCaptor.getValue();

        assertThat(capturedEvent.eventId()).isEqualTo(eventId);
        assertThat(capturedEvent.userId()).isEqualTo(userId);
        assertThat(capturedEvent.model()).isEqualTo("claude-haiku-3-5-20241022");
        assertThat(capturedEvent.date()).isNotNull();
    }

    @Test
    void shouldProcessOpusModelEvent() throws Exception {
        // Given: Opus model usage event
        UsageEventData eventData = new UsageEventData(
            "claude-opus-4-20250514",
            "msg-opus-123",
            5000,
            2000,
            500,
            1000,
            8500,
            5000L,
            true,
            "end_turn",
            "success",
            null,  // errorType
            "enterprise-key",
            "trace-opus",
            "anthro-opus-req"
        );

        String eventId = UUID.randomUUID().toString();
        String userId = "enterprise-user";
        OffsetDateTime eventTime = OffsetDateTime.now();
        byte[] payload = objectMapper.writeValueAsBytes(eventData);

        Message<byte[]> message = MessageBuilder.withPayload(payload)
            .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON)
            .setHeader(CloudEventMessageUtils.ID, eventId)
            .setHeader(CloudEventMessageUtils.SOURCE, URI.create("https://gate.example.com"))
            .setHeader(CloudEventMessageUtils.TYPE, "io.github.samzhu.gate.usage.v1")
            .setHeader(CloudEventMessageUtils.SUBJECT, userId)
            .setHeader(CloudEventMessageUtils.TIME, eventTime)
            .setHeader(CloudEventMessageUtils.SPECVERSION, "1.0")
            .build();

        // When
        inputDestination.send(message);

        // Then
        ArgumentCaptor<UsageEvent> eventCaptor = ArgumentCaptor.forClass(UsageEvent.class);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            verify(mockBufferService, atLeastOnce()).addEvent(eventCaptor.capture());
        });

        UsageEvent capturedEvent = eventCaptor.getValue();

        assertThat(capturedEvent.eventId()).isEqualTo(eventId);
        assertThat(capturedEvent.model()).isEqualTo("claude-opus-4-20250514");
        assertThat(capturedEvent.inputTokens()).isEqualTo(5000);
        assertThat(capturedEvent.outputTokens()).isEqualTo(2000);
        assertThat(capturedEvent.cacheCreationTokens()).isEqualTo(500);
        assertThat(capturedEvent.cacheReadTokens()).isEqualTo(1000);
    }

    @Test
    void shouldHandleErrorStatus() throws Exception {
        // Given: Event with error status
        UsageEventData eventData = new UsageEventData(
            "claude-sonnet-4-20250514",
            "msg-error",
            100,
            0,
            0,
            0,
            100,
            50L,
            false,
            null,
            "error",
            "rate_limit_error",  // errorType
            "test-key",
            "trace-error",
            "anthro-error-req"
        );

        String eventId = UUID.randomUUID().toString();
        String userId = "error-user";
        byte[] payload = objectMapper.writeValueAsBytes(eventData);

        Message<byte[]> message = MessageBuilder.withPayload(payload)
            .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON)
            .setHeader(CloudEventMessageUtils.ID, eventId)
            .setHeader(CloudEventMessageUtils.SOURCE, URI.create("https://gate.example.com"))
            .setHeader(CloudEventMessageUtils.TYPE, "io.github.samzhu.gate.usage.v1")
            .setHeader(CloudEventMessageUtils.SUBJECT, userId)
            .setHeader(CloudEventMessageUtils.SPECVERSION, "1.0")
            .build();

        // When
        inputDestination.send(message);

        // Then
        ArgumentCaptor<UsageEvent> eventCaptor = ArgumentCaptor.forClass(UsageEvent.class);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            verify(mockBufferService, atLeastOnce()).addEvent(eventCaptor.capture());
        });

        UsageEvent capturedEvent = eventCaptor.getValue();

        assertThat(capturedEvent.eventId()).isEqualTo(eventId);
        assertThat(capturedEvent.status()).isEqualTo("error");
        assertThat(capturedEvent.errorType()).isEqualTo("rate_limit_error");
        assertThat(capturedEvent.isSuccess()).isFalse();
        assertThat(capturedEvent.outputTokens()).isZero();
    }

    @Configuration
    @EnableAutoConfiguration(exclude = {
        MongoAutoConfiguration.class,
        MongoDataAutoConfiguration.class
    }, excludeName = {
        "com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubAutoConfiguration",
        "com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubReactiveAutoConfiguration",
        "com.google.cloud.spring.autoconfigure.pubsub.stream.GcpPubSubBinderAutoConfiguration",
        "com.google.cloud.spring.autoconfigure.core.GcpContextAutoConfiguration"
    })
    @Import(UsageEventFunction.class)
    static class TestConfig {

        @Bean
        public EventBufferService eventBufferService() {
            return mockBufferService;
        }
    }
}

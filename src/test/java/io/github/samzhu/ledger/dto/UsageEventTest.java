package io.github.samzhu.ledger.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class UsageEventTest {

    @Test
    void shouldReturnTrueForSuccessStatus() {
        // Given
        UsageEvent event = createEvent("success");

        // When & Then
        assertThat(event.isSuccess()).isTrue();
    }

    @Test
    void shouldReturnTrueForSuccessStatusCaseInsensitive() {
        // Given
        UsageEvent event1 = createEvent("SUCCESS");
        UsageEvent event2 = createEvent("Success");
        UsageEvent event3 = createEvent("SuCcEsS");

        // When & Then
        assertThat(event1.isSuccess()).isTrue();
        assertThat(event2.isSuccess()).isTrue();
        assertThat(event3.isSuccess()).isTrue();
    }

    @Test
    void shouldReturnFalseForErrorStatus() {
        // Given
        UsageEvent event = createEvent("error");

        // When & Then
        assertThat(event.isSuccess()).isFalse();
    }

    @Test
    void shouldReturnFalseForNullStatus() {
        // Given
        UsageEvent event = createEvent(null);

        // When & Then
        assertThat(event.isSuccess()).isFalse();
    }

    @Test
    void shouldReturnFalseForOtherStatus() {
        // Given
        UsageEvent event1 = createEvent("failed");
        UsageEvent event2 = createEvent("timeout");
        UsageEvent event3 = createEvent("");

        // When & Then
        assertThat(event1.isSuccess()).isFalse();
        assertThat(event2.isSuccess()).isFalse();
        assertThat(event3.isSuccess()).isFalse();
    }

    @Test
    void shouldStoreAllFieldsCorrectly() {
        // Given
        LocalDate date = LocalDate.of(2025, 12, 9);
        // UsageEventData 只包含原始數據，totalTokens 由 UsageEvent 計算
        UsageEventData data = new UsageEventData(
            "claude-sonnet-4-20250514",  // model
            1000,                         // inputTokens (非快取輸入)
            500,                          // outputTokens
            100,                          // cacheCreationTokens
            200,                          // cacheReadTokens
            "msg-abc",                    // messageId
            1500L,                        // latencyMs
            true,                         // stream
            "end_turn",                   // stopReason
            "success",                    // status
            null,                         // errorType
            "primary-key",                // keyAlias
            "trace-789",                  // traceId
            "anthro-req-xyz"              // anthropicRequestId
        );

        // When
        java.time.Instant timestamp = java.time.Instant.now();
        UsageEvent event = new UsageEvent(
            "event-123",
            "user-456",
            date,
            timestamp,
            data
        );

        // Then
        assertThat(event.eventId()).isEqualTo("event-123");
        assertThat(event.userId()).isEqualTo("user-456");
        assertThat(event.date()).isEqualTo(date);
        assertThat(event.data()).isEqualTo(data);
        // Convenience accessors
        assertThat(event.model()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(event.inputTokens()).isEqualTo(1000);
        assertThat(event.outputTokens()).isEqualTo(500);
        assertThat(event.cacheCreationTokens()).isEqualTo(100);
        assertThat(event.cacheReadTokens()).isEqualTo(200);
        // 計算方法: totalInputTokens = 1000 + 100 + 200 = 1300
        assertThat(event.totalInputTokens()).isEqualTo(1300);
        // 計算方法: totalTokens = 1300 + 500 = 1800
        assertThat(event.totalTokens()).isEqualTo(1800);
        assertThat(event.latencyMs()).isEqualTo(1500L);
        assertThat(event.stream()).isTrue();
        assertThat(event.stopReason()).isEqualTo("end_turn");
        assertThat(event.status()).isEqualTo("success");
        assertThat(event.keyAlias()).isEqualTo("primary-key");
        assertThat(event.traceId()).isEqualTo("trace-789");
        assertThat(event.messageId()).isEqualTo("msg-abc");
        assertThat(event.anthropicRequestId()).isEqualTo("anthro-req-xyz");
    }

    private UsageEvent createEvent(String status) {
        UsageEventData data = new UsageEventData(
            "claude-sonnet-4-20250514",  // model
            100,                          // inputTokens
            50,                           // outputTokens
            0,                            // cacheCreationTokens
            0,                            // cacheReadTokens
            "msg-1",                      // messageId
            1000L,                        // latencyMs
            false,                        // stream
            "end_turn",                   // stopReason
            status,                       // status
            null,                         // errorType
            "primary",                    // keyAlias
            "trace-1",                    // traceId
            "req-1"                       // anthropicRequestId
        );
        return new UsageEvent("event-1", "user-1", LocalDate.now(), java.time.Instant.now(), data);
    }
}

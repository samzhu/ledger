package io.github.samzhu.ledger.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class DocumentIdTest {

    @Test
    void dailyUserUsageShouldCreateCorrectId() {
        // Given
        LocalDate date = LocalDate.of(2025, 12, 9);
        String userId = "user-uuid-123";

        // When
        String id = DailyUserUsage.createId(date, userId);

        // Then
        assertThat(id).isEqualTo("2025-12-09_user-uuid-123");
    }

    @Test
    void dailyModelUsageShouldCreateCorrectId() {
        // Given
        LocalDate date = LocalDate.of(2025, 12, 9);
        String model = "claude-sonnet-4-20250514";

        // When
        String id = DailyModelUsage.createId(date, model);

        // Then
        assertThat(id).isEqualTo("2025-12-09_claude-sonnet-4-20250514");
    }

    @Test
    void shouldHandleDifferentDates() {
        // Given
        LocalDate date1 = LocalDate.of(2025, 1, 1);
        LocalDate date2 = LocalDate.of(2025, 12, 31);
        String userId = "test-user";

        // When & Then
        assertThat(DailyUserUsage.createId(date1, userId)).isEqualTo("2025-01-01_test-user");
        assertThat(DailyUserUsage.createId(date2, userId)).isEqualTo("2025-12-31_test-user");
    }

    @Test
    void shouldHandleSpecialCharactersInUserId() {
        // Given
        LocalDate date = LocalDate.of(2025, 6, 15);
        String userId = "user@example.com";

        // When
        String id = DailyUserUsage.createId(date, userId);

        // Then
        assertThat(id).isEqualTo("2025-06-15_user@example.com");
    }
}

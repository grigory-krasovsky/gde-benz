package ru.gdebenz.user;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FuelFilterServiceTest {

    @Test
    void parseReturnsKnownGradesInCanonicalOrder() {
        assertThat(FuelFilterService.parse("98,92")).containsExactly("92", "98");
        assertThat(FuelFilterService.parse("ДТ,95")).containsExactly("95", "ДТ");
    }

    @Test
    void parseIgnoresUnknownAndBlankTokens() {
        assertThat(FuelFilterService.parse("95, foo ,,80")).containsExactly("95");
        assertThat(FuelFilterService.parse(null)).isEmpty();
        assertThat(FuelFilterService.parse("")).isEmpty();
    }
}

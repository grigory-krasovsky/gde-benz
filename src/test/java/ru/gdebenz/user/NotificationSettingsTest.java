package ru.gdebenz.user;

import org.junit.jupiter.api.Test;
import ru.gdebenz.user.NotificationSettingsService.Settings;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for quiet-hours membership and hour stepping. */
class NotificationSettingsTest {

    @Test
    void quietHoursWrapPastMidnight() {
        Settings s = new Settings(true, false, true, true, 23, 7);
        assertThat(s.isQuiet(23)).isTrue();
        assertThat(s.isQuiet(3)).isTrue();
        assertThat(s.isQuiet(7)).isFalse();
        assertThat(s.isQuiet(12)).isFalse();
    }

    @Test
    void quietHoursWithinOneDay() {
        Settings s = new Settings(true, false, true, true, 13, 15);
        assertThat(s.isQuiet(13)).isTrue();
        assertThat(s.isQuiet(14)).isTrue();
        assertThat(s.isQuiet(15)).isFalse();
    }

    @Test
    void disabledQuietHoursAreNeverQuiet() {
        assertThat(new Settings(true, false, true, false, 23, 7).isQuiet(2)).isFalse();
    }

    @Test
    void shiftWrapsAndSkipsTheOtherBound() {
        assertThat(NotificationSettingsService.shift(23, 1, 7)).isZero();
        assertThat(NotificationSettingsService.shift(0, -1, 7)).isEqualTo(23);
        assertThat(NotificationSettingsService.shift(6, 1, 7)).isEqualTo(8);
        assertThat(NotificationSettingsService.shift(8, -1, 7)).isEqualTo(6);
    }
}

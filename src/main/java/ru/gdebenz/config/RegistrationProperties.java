package ru.gdebenz.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Registration settings, bound from {@code gdebenz.registration.*}.
 *
 * @param rejectCooldown how long a rejected user must wait before {@code /register} is accepted again
 */
@ConfigurationProperties(prefix = "gdebenz.registration")
public record RegistrationProperties(Duration rejectCooldown) {

    public RegistrationProperties {
        if (rejectCooldown == null) {
            rejectCooldown = Duration.ofMinutes(30);
        }
    }
}

package ru.gdebenz.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Telegram bot settings, bound from the {@code telegram.bot.*} configuration keys.
 * The token is expected to come from the {@code TELEGRAM_BOT_TOKEN} environment variable
 * (see {@code application.yml}) and must never be committed to the repository.
 */
@ConfigurationProperties(prefix = "telegram.bot")
public record TelegramBotProperties(String token, String username) {
}

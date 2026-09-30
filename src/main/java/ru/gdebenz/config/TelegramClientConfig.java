package ru.gdebenz.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Shared {@link TelegramClient} used to send replies, edit messages and register command menus.
 * Separate from the long-polling consumer, which only needs the bot token.
 */
@Configuration
public class TelegramClientConfig {

    @Bean
    public TelegramClient telegramClient(TelegramBotProperties properties) {
        return new OkHttpTelegramClient(properties.token());
    }
}

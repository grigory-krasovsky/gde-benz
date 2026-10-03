package ru.gdebenz.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

/**
 * Registers the default command menu on startup so users see the command list in Telegram.
 * Skipped when the bot is disabled (e.g. in tests) to avoid a network call to Telegram.
 */
@Component
@ConditionalOnProperty(name = "telegrambots.enabled", havingValue = "true", matchIfMissing = true)
public class BotMenuConfigurer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BotMenuConfigurer.class);

    private final TelegramClient client;

    public BotMenuConfigurer(TelegramClient client) {
        this.client = client;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<BotCommand> commands = List.of(
                BotCommand.builder().command("nearby").description("Показать ближайшие АЗС").build(),
                BotCommand.builder().command("filters").description("Фильтр по топливу").build(),
                BotCommand.builder().command("register").description("Запросить доступ").build(),
                BotCommand.builder().command("help").description("Помощь").build(),
                BotCommand.builder().command("start").description("Начать").build()
        );
        try {
            // No scope -> applies to the default scope (all private chats).
            client.execute(SetMyCommands.builder().commands(commands).build());
            log.info("Default bot command menu registered");
        } catch (TelegramApiException e) {
            log.warn("Failed to register bot command menu", e);
        }
    }
}

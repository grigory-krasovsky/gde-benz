package ru.gdebenz.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.DefaultLongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.gdebenz.config.TelegramBotProperties;

/**
 * Long-polling Telegram bot.
 *
 * <p>The {@code telegrambots-springboot-longpolling-starter} auto-configuration discovers this
 * {@link SpringLongPollingBot} bean, opens the {@code getUpdates} long-polling connection using
 * {@link #getBotToken()}, and dispatches every {@link Update} to {@link #consume(Update)}.
 */
@Component
public class GdeBenzBot extends DefaultLongPollingUpdateConsumer implements SpringLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(GdeBenzBot.class);

    private final String botToken;
    private final TelegramClient telegramClient;

    public GdeBenzBot(TelegramBotProperties properties) {
        this.botToken = properties.token();
        this.telegramClient = new OkHttpTelegramClient(this.botToken);
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText();

        String reply = switch (text) {
            case "/start" -> "Привет! Я gde-benz бот. Напиши что-нибудь — я повторю.";
            default -> text;
        };

        sendText(chatId, reply);
    }

    private void sendText(long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .build();
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chat {}", chatId, e);
        }
    }
}

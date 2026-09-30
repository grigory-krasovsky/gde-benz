package ru.gdebenz.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.DefaultLongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeChat;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.gdebenz.config.TelegramBotProperties;
import ru.gdebenz.user.BotUser;
import ru.gdebenz.user.RegistrationService;
import ru.gdebenz.user.TgUser;

import java.util.List;

/**
 * Long-polling Telegram bot. Handles the registration/approval flow:
 * users send {@code /register}; the first one becomes admin, others wait for approval via
 * inline buttons the admin taps.
 */
@Component
public class GdeBenzBot extends DefaultLongPollingUpdateConsumer implements SpringLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(GdeBenzBot.class);

    private static final String CB_APPROVE = "reg:approve:";
    private static final String CB_REJECT = "reg:reject:";

    private final String botToken;
    private final TelegramClient client;
    private final RegistrationService registration;

    public GdeBenzBot(TelegramBotProperties properties, TelegramClient client, RegistrationService registration) {
        this.botToken = properties.token();
        this.client = client;
        this.registration = registration;
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
        try {
            if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
                return;
            }
            if (!update.hasMessage() || !update.getMessage().hasText()) {
                return;
            }
            User from = update.getMessage().getFrom();
            long chatId = update.getMessage().getChatId();
            String command = parseCommand(update.getMessage().getText());
            switch (command) {
                case "/start" -> onStart(chatId);
                case "/help" -> onHelp(from, chatId);
                case "/register" -> onRegister(from, chatId);
                case "/pending" -> onPending(from, chatId);
                default -> onUnknown(from, chatId);
            }
        } catch (Exception e) {
            log.error("Failed to handle update", e);
        }
    }

    /** Extracts the command word, lower-cased and without a {@code @BotName} suffix. */
    private String parseCommand(String text) {
        String first = text.trim().split("\\s+")[0].toLowerCase();
        int at = first.indexOf('@');
        return at >= 0 ? first.substring(0, at) : first;
    }

    // ---- command handlers ----

    private void onStart(long chatId) {
        sendText(chatId, """
                👋 Привет! Это бот gde-benz — показывает наличие топлива на ближайших АЗС.
                Бот приватный. Чтобы получить доступ, отправь /register.""");
    }

    private void onHelp(User from, long chatId) {
        StringBuilder sb = new StringBuilder("""
                Команды:
                /register — запросить доступ
                /help — эта справка""");
        if (registration.isAdmin(from.getId())) {
            sb.append("\n/pending — заявки на доступ (админ)");
        }
        sendText(chatId, sb.toString());
    }

    private void onRegister(User from, long chatId) {
        TgUser tg = new TgUser(from.getId(), chatId, from.getUserName(), from.getFirstName());
        RegistrationService.RegisterResult result = registration.register(tg);
        switch (result.outcome()) {
            case BECAME_ADMIN -> {
                sendText(chatId, "✅ Ты первый пользователь — назначен администратором. "
                        + "Заявки на доступ будут приходить сюда.");
                setAdminMenu(chatId);
            }
            case PENDING_CREATED, REREQUESTED -> {
                sendText(chatId, "📨 Заявка отправлена. Дождись одобрения администратора.");
                for (Long adminChatId : result.adminChatIds()) {
                    sendRequestCard(adminChatId, result.user());
                }
            }
            case ALREADY_APPROVED -> sendText(chatId, "✅ У тебя уже есть доступ.");
            case ALREADY_PENDING -> sendText(chatId, "⏳ Твоя заявка уже на рассмотрении.");
            case REJECTED_COOLDOWN -> {
                long minutes = Math.max(1, result.cooldownRemaining().toMinutes());
                sendText(chatId, "🚫 Заявка отклонена. Повторить можно через " + minutes + " мин.");
            }
        }
    }

    private void onPending(User from, long chatId) {
        if (!registration.isAdmin(from.getId())) {
            sendText(chatId, "Команда доступна только администратору.");
            return;
        }
        List<BotUser> pending = registration.pending();
        if (pending.isEmpty()) {
            sendText(chatId, "Заявок нет.");
            return;
        }
        sendText(chatId, "Заявки на доступ: " + pending.size());
        for (BotUser user : pending) {
            sendRequestCard(chatId, user);
        }
    }

    private void onUnknown(User from, long chatId) {
        if (registration.isAdmin(from.getId())) {
            sendText(chatId, "Не понял команду. /help");
        } else {
            sendText(chatId, "Нужен доступ. Отправь /register, затем дождись одобрения.");
        }
    }

    // ---- inline approve/reject ----

    private void handleCallback(CallbackQuery cb) {
        String data = cb.getData() == null ? "" : cb.getData();
        boolean approve = data.startsWith(CB_APPROVE);
        boolean reject = data.startsWith(CB_REJECT);
        if (!approve && !reject) {
            answerCallback(cb.getId(), "Неизвестное действие.");
            return;
        }
        long targetId;
        try {
            String prefix = approve ? CB_APPROVE : CB_REJECT;
            targetId = Long.parseLong(data.substring(prefix.length()));
        } catch (NumberFormatException e) {
            answerCallback(cb.getId(), "Некорректные данные.");
            return;
        }

        RegistrationService.DecisionResult result = registration.decide(cb.getFrom().getId(), targetId, approve);
        switch (result.decision()) {
            case NOT_AUTHORIZED -> answerCallback(cb.getId(), "Только администратор может одобрять заявки.");
            case TARGET_NOT_FOUND -> answerCallback(cb.getId(), "Пользователь не найден.");
            case ALREADY_DECIDED -> {
                answerCallback(cb.getId(), "Заявка уже обработана.");
                editText(cb, "Заявка уже обработана: " + display(result.target()));
            }
            case DONE_APPROVED -> {
                answerCallback(cb.getId(), "Одобрено");
                editText(cb, "✅ Одобрен: " + display(result.target()));
                sendText(result.target().getChatId(), "🎉 Доступ одобрен! Теперь тебе доступны команды бота. /help");
            }
            case DONE_REJECTED -> {
                answerCallback(cb.getId(), "Отклонено");
                editText(cb, "🚫 Отклонён: " + display(result.target()));
                sendText(result.target().getChatId(), "К сожалению, в доступе отказано.");
            }
        }
    }

    private void sendRequestCard(long adminChatId, BotUser requester) {
        InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(InlineKeyboardButton.builder()
                .text("✅ Одобрить")
                .callbackData(CB_APPROVE + requester.getUserId())
                .build());
        row.add(InlineKeyboardButton.builder()
                .text("🚫 Отклонить")
                .callbackData(CB_REJECT + requester.getUserId())
                .build());
        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder().keyboardRow(row).build();

        SendMessage message = SendMessage.builder()
                .chatId(adminChatId)
                .text("🆕 Запрос доступа: " + display(requester))
                .replyMarkup(markup)
                .build();
        send(message);
    }

    private String display(BotUser user) {
        StringBuilder sb = new StringBuilder();
        if (user.getFirstName() != null) {
            sb.append(user.getFirstName());
        }
        if (user.getUsername() != null) {
            sb.append(" (@").append(user.getUsername()).append(')');
        }
        sb.append(" [id ").append(user.getUserId()).append(']');
        return sb.toString().trim();
    }

    // ---- admin menu ----

    private void setAdminMenu(long adminChatId) {
        List<BotCommand> commands = List.of(
                BotCommand.builder().command("pending").description("Заявки на доступ").build(),
                BotCommand.builder().command("help").description("Помощь").build()
        );
        try {
            client.execute(SetMyCommands.builder()
                    .commands(commands)
                    .scope(BotCommandScopeChat.builder().chatId(String.valueOf(adminChatId)).build())
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Failed to set admin menu for chat {}", adminChatId, e);
        }
    }

    // ---- telegram I/O helpers ----

    private void sendText(long chatId, String text) {
        send(SendMessage.builder().chatId(chatId).text(text).build());
    }

    private void send(SendMessage message) {
        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chat {}", message.getChatId(), e);
        }
    }

    private void answerCallback(String callbackId, String text) {
        try {
            client.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).text(text).build());
        } catch (TelegramApiException e) {
            log.error("Failed to answer callback {}", callbackId, e);
        }
    }

    private void editText(CallbackQuery cb, String text) {
        try {
            client.execute(EditMessageText.builder()
                    .chatId(String.valueOf(cb.getMessage().getChatId()))
                    .messageId(cb.getMessage().getMessageId())
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to edit message", e);
        }
    }
}

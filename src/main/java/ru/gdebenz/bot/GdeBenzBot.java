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
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeChat;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.gdebenz.config.TelegramBotProperties;
import ru.gdebenz.fuel.FuelAvailabilityService;
import ru.gdebenz.fuel.FuelStatus;
import ru.gdebenz.fuel.GdeBenzApiClient;
import ru.gdebenz.fuel.NearbyFormatter;
import ru.gdebenz.fuel.NearbyResult;
import ru.gdebenz.fuel.StationView;
import ru.gdebenz.user.BotUser;
import ru.gdebenz.user.RegistrationService;
import ru.gdebenz.user.TgUser;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Long-polling Telegram bot.
 *
 * <p>Registration: {@code /register}; the first registrant becomes admin, others wait for
 * inline approve/reject. Nearby: approved users tap an inline button, share a location via a
 * one-tap reply button, and get a single result message they can refresh in place; each
 * available station has a "comments" button that opens recent driver reports. Transient
 * messages (prompt, the shared location) are deleted to keep the chat clean.
 */
@Component
public class GdeBenzBot extends DefaultLongPollingUpdateConsumer implements SpringLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(GdeBenzBot.class);

    private static final String CB_APPROVE = "reg:approve:";
    private static final String CB_REJECT = "reg:reject:";
    private static final String CB_NEARBY = "nb:req";
    private static final String CB_REFRESH = "nb:ref:";
    private static final String CB_COMMENTS = "cmt:";
    private static final String CB_COMMENTS_CLOSE = "cmt:close";

    private static final int MAX_COMMENT_BUTTONS = 6;

    private final String botToken;
    private final TelegramClient client;
    private final RegistrationService registration;
    private final FuelAvailabilityService fuel;
    private final NearbyFormatter formatter;

    /** Per-chat id of the "share your location" prompt, so we can delete it afterwards. */
    private final Map<Long, Integer> promptByChat = new ConcurrentHashMap<>();

    public GdeBenzBot(TelegramBotProperties properties, TelegramClient client,
                      RegistrationService registration, FuelAvailabilityService fuel,
                      NearbyFormatter formatter) {
        this.botToken = properties.token();
        this.client = client;
        this.registration = registration;
        this.fuel = fuel;
        this.formatter = formatter;
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
            if (!update.hasMessage()) {
                return;
            }
            var message = update.getMessage();
            User from = message.getFrom();
            long chatId = message.getChatId();

            if (message.hasLocation()) {
                var location = message.getLocation();
                onLocation(from, chatId, message.getMessageId(),
                        location.getLatitude(), location.getLongitude());
                return;
            }
            if (!message.hasText()) {
                return;
            }
            switch (parseCommand(message.getText())) {
                case "/start" -> onStart(from, chatId);
                case "/help" -> onHelp(from, chatId);
                case "/register" -> onRegister(from, chatId);
                case "/pending" -> onPending(from, chatId);
                case "/nearby" -> onNearby(from, chatId);
                default -> onUnknown(from, chatId);
            }
        } catch (Exception e) {
            log.error("Failed to handle update", e);
        }
    }

    private String parseCommand(String text) {
        String first = text.trim().split("\\s+")[0].toLowerCase();
        int at = first.indexOf('@');
        return at >= 0 ? first.substring(0, at) : first;
    }

    // ---- commands ----

    private void onStart(User from, long chatId) {
        if (registration.isApproved(from.getId())) {
            sendMainMenu(chatId);
        } else {
            sendText(chatId, """
                    👋 Привет! Это бот gde-benz — показывает наличие топлива на ближайших АЗС.
                    Бот приватный. Чтобы получить доступ, отправь /register.""");
        }
    }

    private void onHelp(User from, long chatId) {
        StringBuilder sb = new StringBuilder("Команды:\n");
        if (registration.isApproved(from.getId())) {
            sb.append("/nearby — показать АЗС рядом\n");
        } else {
            sb.append("/register — запросить доступ\n");
        }
        sb.append("/help — эта справка");
        if (registration.isAdmin(from.getId())) {
            sb.append("\n/pending — заявки на доступ (админ)");
        }
        sendText(chatId, sb.toString());
    }

    private void onNearby(User from, long chatId) {
        if (!registration.isApproved(from.getId())) {
            sendText(chatId, "Нужен доступ. Отправь /register, затем дождись одобрения.");
            return;
        }
        sendLocationPrompt(chatId);
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
            case ALREADY_APPROVED -> sendText(chatId, "✅ У тебя уже есть доступ. /nearby");
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
        if (registration.isApproved(from.getId())) {
            sendMainMenu(chatId);
        } else {
            sendText(chatId, "Нужен доступ. Отправь /register, затем дождись одобрения.");
        }
    }

    // ---- nearby flow ----

    private void sendMainMenu(long chatId) {
        InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(InlineKeyboardButton.builder().text("📍 Показать АЗС рядом").callbackData(CB_NEARBY).build());
        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder().keyboardRow(row).build();
        send(SendMessage.builder()
                .chatId(chatId)
                .text("Готов искать топливо. Жми кнопку 👇")
                .replyMarkup(markup)
                .build());
    }

    private void sendLocationPrompt(long chatId) {
        KeyboardRow row = new KeyboardRow();
        row.add(KeyboardButton.builder().text("📍 Отправить геопозицию").requestLocation(true).build());
        ReplyKeyboardMarkup keyboard = ReplyKeyboardMarkup.builder()
                .keyboard(List.of(row))
                .resizeKeyboard(true)
                .oneTimeKeyboard(true)
                .selective(true)
                .build();
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("Нажми кнопку ниже, чтобы отправить свою геопозицию 👇")
                .replyMarkup(keyboard)
                .build();
        try {
            var sent = client.execute(message);
            if (sent != null) {
                promptByChat.put(chatId, sent.getMessageId());
            }
        } catch (TelegramApiException e) {
            log.error("Failed to send location prompt to chat {}", chatId, e);
        }
    }

    private void onLocation(User from, long chatId, int locationMessageId, double lat, double lon) {
        if (!registration.isApproved(from.getId())) {
            sendText(chatId, "Нужен доступ. Отправь /register, затем дождись одобрения.");
            return;
        }
        // Keep the chat clean: drop the prompt and the shared-location message.
        Integer promptId = promptByChat.remove(chatId);
        if (promptId != null) {
            deleteMessage(chatId, promptId);
        }
        deleteMessage(chatId, locationMessageId);

        NearbyResult result;
        try {
            result = fuel.nearby(lat, lon);
        } catch (GdeBenzApiClient.ApiException e) {
            log.warn("nearby request failed for chat {}", chatId, e);
            sendText(chatId, "Источник данных сейчас недоступен. Попробуй через минуту.");
            return;
        }
        send(SendMessage.builder()
                .chatId(chatId)
                .text(formatter.format(result, lat, lon))
                .parseMode("HTML")
                .replyMarkup(nearbyKeyboard(lat, lon, result))
                .build());
    }

    private void handleRefresh(CallbackQuery cb, String data) {
        double[] point = parsePoint(data.substring(CB_REFRESH.length()));
        if (point == null) {
            answerCallback(cb.getId(), "Некорректные данные.");
            return;
        }
        if (!registration.isApproved(cb.getFrom().getId())) {
            answerCallback(cb.getId(), "Нужен доступ.");
            return;
        }
        NearbyResult result;
        try {
            result = fuel.nearby(point[0], point[1]);
        } catch (GdeBenzApiClient.ApiException e) {
            answerCallback(cb.getId(), "Источник недоступен, попробуй позже.");
            return;
        }
        answerCallback(cb.getId(), "Обновлено");
        try {
            client.execute(EditMessageText.builder()
                    .chatId(String.valueOf(cb.getMessage().getChatId()))
                    .messageId(cb.getMessage().getMessageId())
                    .text(formatter.format(result, point[0], point[1]))
                    .parseMode("HTML")
                    .replyMarkup(nearbyKeyboard(point[0], point[1], result))
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to edit nearby result", e);
        }
    }

    private void onComments(CallbackQuery cb, String osmId) {
        if (!registration.isApproved(cb.getFrom().getId())) {
            answerCallback(cb.getId(), "Нужен доступ.");
            return;
        }
        String text;
        try {
            text = formatter.formatComments(fuel.comments(osmId));
        } catch (GdeBenzApiClient.ApiException e) {
            answerCallback(cb.getId(), "Не удалось загрузить отметки.");
            return;
        }
        answerCallback(cb.getId());
        InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(InlineKeyboardButton.builder().text("✖ Закрыть").callbackData(CB_COMMENTS_CLOSE).build());
        send(SendMessage.builder()
                .chatId(cb.getMessage().getChatId())
                .text(text)
                .parseMode("HTML")
                .replyMarkup(InlineKeyboardMarkup.builder().keyboardRow(row).build())
                .build());
    }

    private InlineKeyboardMarkup nearbyKeyboard(double lat, double lon, NearbyResult result) {
        var builder = InlineKeyboardMarkup.builder();

        InlineKeyboardRow refresh = new InlineKeyboardRow();
        refresh.add(InlineKeyboardButton.builder()
                .text("🔄 Обновить")
                .callbackData(String.format(Locale.ROOT, "%s%.5f:%.5f", CB_REFRESH, lat, lon))
                .build());
        builder.keyboardRow(refresh);

        int added = 0;
        for (StationView s : result.stations()) {
            if (added >= MAX_COMMENT_BUTTONS) {
                break;
            }
            if (s.status() == FuelStatus.AVAILABLE || s.status() == FuelStatus.QUEUE) {
                InlineKeyboardRow row = new InlineKeyboardRow();
                row.add(InlineKeyboardButton.builder()
                        .text(String.format(Locale.ROOT, "💬 %s · %.1f км", s.brand(), s.distanceKm()))
                        .callbackData(CB_COMMENTS + s.osmId())
                        .build());
                builder.keyboardRow(row);
                added++;
            }
        }
        return builder.build();
    }

    private static double[] parsePoint(String latLon) {
        String[] parts = latLon.split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new double[] {Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- callbacks ----

    private void handleCallback(CallbackQuery cb) {
        String data = cb.getData() == null ? "" : cb.getData();
        if (data.startsWith(CB_APPROVE) || data.startsWith(CB_REJECT)) {
            handleRegistrationDecision(cb, data);
        } else if (data.equals(CB_NEARBY)) {
            onNearbyCallback(cb);
        } else if (data.startsWith(CB_REFRESH)) {
            handleRefresh(cb, data);
        } else if (data.equals(CB_COMMENTS_CLOSE)) {
            answerCallback(cb.getId());
            deleteMessage(cb.getMessage().getChatId(), cb.getMessage().getMessageId());
        } else if (data.startsWith(CB_COMMENTS)) {
            onComments(cb, data.substring(CB_COMMENTS.length()));
        } else {
            answerCallback(cb.getId(), "Неизвестное действие.");
        }
    }

    private void onNearbyCallback(CallbackQuery cb) {
        answerCallback(cb.getId());
        if (!registration.isApproved(cb.getFrom().getId())) {
            sendText(cb.getMessage().getChatId(), "Нужен доступ. Отправь /register.");
            return;
        }
        sendLocationPrompt(cb.getMessage().getChatId());
    }

    private void handleRegistrationDecision(CallbackQuery cb, String data) {
        boolean approve = data.startsWith(CB_APPROVE);
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
                setApprovedMenu(result.target().getChatId());
                sendText(result.target().getChatId(),
                        "🎉 Доступ одобрен! Жми /nearby, чтобы найти топливо рядом.");
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

        send(SendMessage.builder()
                .chatId(adminChatId)
                .text("🆕 Запрос доступа: " + display(requester))
                .replyMarkup(markup)
                .build());
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

    // ---- command menus ----

    private void setApprovedMenu(long chatId) {
        setMenu(chatId, List.of(
                BotCommand.builder().command("nearby").description("АЗС рядом").build(),
                BotCommand.builder().command("help").description("Помощь").build()));
    }

    private void setAdminMenu(long chatId) {
        setMenu(chatId, List.of(
                BotCommand.builder().command("nearby").description("АЗС рядом").build(),
                BotCommand.builder().command("pending").description("Заявки на доступ").build(),
                BotCommand.builder().command("help").description("Помощь").build()));
    }

    private void setMenu(long chatId, List<BotCommand> commands) {
        try {
            client.execute(SetMyCommands.builder()
                    .commands(commands)
                    .scope(BotCommandScopeChat.builder().chatId(String.valueOf(chatId)).build())
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Failed to set command menu for chat {}", chatId, e);
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

    private void deleteMessage(long chatId, int messageId) {
        try {
            client.execute(DeleteMessage.builder().chatId(String.valueOf(chatId)).messageId(messageId).build());
        } catch (TelegramApiException e) {
            log.debug("Failed to delete message {} in chat {}", messageId, chatId, e);
        }
    }

    private void answerCallback(String callbackId) {
        answerCallback(callbackId, null);
    }

    private void answerCallback(String callbackId, String text) {
        try {
            var builder = AnswerCallbackQuery.builder().callbackQueryId(callbackId);
            if (text != null) {
                builder.text(text);
            }
            client.execute(builder.build());
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

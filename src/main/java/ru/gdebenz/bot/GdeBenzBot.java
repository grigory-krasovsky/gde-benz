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
import ru.gdebenz.fuel.GdeBenzApiClient;
import ru.gdebenz.fuel.NearbyFormatter;
import ru.gdebenz.fuel.NearbyResult;
import ru.gdebenz.fuel.StationView;
import ru.gdebenz.fuel.tbank.TbankService;
import ru.gdebenz.fuel.tbank.TbankSnapshot;
import ru.gdebenz.fuel.tbank.TbankStation;
import ru.gdebenz.user.BotUser;
import ru.gdebenz.user.FavoriteService;
import ru.gdebenz.user.FuelFilterService;
import ru.gdebenz.user.NotificationSettingsService;
import ru.gdebenz.user.RegistrationService;
import ru.gdebenz.user.TgUser;
import ru.gdebenz.watch.FavoriteWatcher;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Long-polling Telegram bot (inline-grid variant).
 *
 * <p>Registration: {@code /register}; the first registrant becomes admin, others wait for
 * inline approve/reject. Nearby: approved users share a location and get a single message
 * whose inline keyboard is an aligned grid — a header row plus one row per station
 * (name, fuel columns 92/95/98/100 as a blended gdebenz/T-Bank confidence %, route, comments). Tapping the name or a fuel
 * cell shows a popup; Refresh edits the same message in place.
 */
@Component
public class GdeBenzBot extends DefaultLongPollingUpdateConsumer implements SpringLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(GdeBenzBot.class);

    private static final String CB_APPROVE = "reg:approve:";
    private static final String CB_REJECT = "reg:reject:";
    private static final String CB_NEARBY = "nb:req";
    private static final String CB_GRID_REFRESH = "g:ref:";
    private static final String CB_GRID_INFO = "g:i:";
    private static final String CB_GRID_FUEL = "g:f:";
    private static final String CB_GRID_CMT = "g:c:";
    private static final String CB_GRID_NOOP = "g:noop";
    private static final String CB_GRID_STAR = "g:s:";
    private static final String CB_GRID_STAR_MODE = "g:smode";
    private static final String CB_GRID_STAR_DONE = "g:sdone";
    private static final String CB_COMMENTS_CLOSE = "cmt:close";
    private static final String CB_FILTER_TOGGLE = "flt:t:";
    private static final String CB_FILTER_CLEAR = "flt:clear";
    private static final String CB_FILTER_CLOSE = "flt:close";
    private static final String CB_SET_NOTIFY = "set:n";
    private static final String CB_SET_QUIET = "set:q";
    private static final String CB_SET_FROM = "set:f:";
    private static final String CB_SET_TO = "set:t:";
    private static final String CB_SET_CLOSE = "set:close";

    private static final String[] GRADES = {"92", "95", "98", "100"};
    private static final int MAX_ROWS = 8;
    private static final int NAME_MAX = 10;

    private final String botToken;
    private final TelegramClient client;
    private final RegistrationService registration;
    private final FuelAvailabilityService fuel;
    private final TbankService tbank;
    private final NearbyFormatter formatter;
    private final FuelFilterService filters;
    private final FavoriteService favorites;
    private final NotificationSettingsService notifications;

    /** Per-chat id of the "share your location" prompt, so we can delete it afterwards. */
    private final Map<Long, Integer> promptByChat = new ConcurrentHashMap<>();
    /** Per-chat current grid state so callbacks can resolve a station by its row index. */
    private final Map<Long, GridState> gridByChat = new ConcurrentHashMap<>();

    /**
     * What the grid message currently shows. {@code starMode} = the "⭐ Отметить" mode, where the
     * 💬 column is replaced by ☆/★ toggles.
     */
    private record GridState(double lat, double lon, NearbyResult result, TbankSnapshot tbank, Integer messageId,
                             Set<String> filter, boolean starMode) {

        GridState withStarMode(boolean on) {
            return new GridState(lat, lon, result, tbank, messageId, filter, on);
        }
    }

    public GdeBenzBot(TelegramBotProperties properties, TelegramClient client,
                      RegistrationService registration, FuelAvailabilityService fuel,
                      TbankService tbank, NearbyFormatter formatter, FuelFilterService filters,
                      FavoriteService favorites, NotificationSettingsService notifications) {
        this.botToken = properties.token();
        this.client = client;
        this.registration = registration;
        this.fuel = fuel;
        this.tbank = tbank;
        this.formatter = formatter;
        this.filters = filters;
        this.favorites = favorites;
        this.notifications = notifications;
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
                case "/filters" -> onFilters(from, chatId);
                case "/settings" -> onSettings(from, chatId);
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
            applyMenu(from.getId(), chatId);
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
            sb.append("/filters — фильтр по топливу\n");
            sb.append("⭐ Отметить (под списком АЗС) — избранные АЗС\n");
            sb.append("/settings — оповещения по избранным и тихие часы\n");
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
        applyMenu(from.getId(), chatId);
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

    // ---- nearby (grid) flow ----

    private void sendMainMenu(long chatId) {
        send(SendMessage.builder()
                .chatId(chatId)
                .text("Готов искать топливо. Жми кнопку 👇")
                .replyMarkup(InlineKeyboardMarkup.builder().keyboardRow(rowOf(btn("📍 Показать АЗС рядом", CB_NEARBY))).build())
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
        Set<String> filter = filters.get(from.getId());
        renderGrid(from.getId(), chatId, lat, lon, FuelAvailabilityService.filterByFuels(result, filter), filter);
    }

    /** Sends the grid as a single message, replacing the previous one. */
    private void renderGrid(long userId, long chatId, double lat, double lon, NearbyResult result, Set<String> filter) {
        GridState old = gridByChat.remove(chatId);
        if (old != null && old.messageId() != null) {
            deleteMessage(chatId, old.messageId());
        }
        GridState state = new GridState(lat, lon, result, tbank.snapshot(lat, lon), null, filter, false);
        Set<String> favIds = favorites.ids(userId);
        Integer id = sendReturningId(SendMessage.builder()
                .chatId(chatId)
                .text(gridTitle(state, favIds))
                .parseMode("HTML")
                .replyMarkup(gridKeyboard(state, favIds))
                .build());
        gridByChat.put(chatId, new GridState(lat, lon, result, state.tbank(), id, filter, false));
    }

    /** Re-fetches nearby, applies the user's filter, and edits the existing grid message in place. */
    private void editGrid(long chatId, int messageId, double lat, double lon, long userId) {
        Set<String> filter = filters.get(userId);
        NearbyResult result = FuelAvailabilityService.filterByFuels(fuel.nearby(lat, lon), filter);
        showGrid(chatId, new GridState(lat, lon, result, tbank.snapshot(lat, lon), messageId, filter, false), userId);
    }

    /** Edits the grid message to show {@code state} (no API calls) and remembers it as current. */
    private void showGrid(long chatId, GridState state, long userId) {
        Set<String> favIds = favorites.ids(userId);
        try {
            client.execute(EditMessageText.builder()
                    .chatId(String.valueOf(chatId))
                    .messageId(state.messageId())
                    .text(gridTitle(state, favIds))
                    .parseMode("HTML")
                    .replyMarkup(gridKeyboard(state, favIds))
                    .build());
            gridByChat.put(chatId, state);
        } catch (TelegramApiException e) {
            log.error("Failed to edit grid", e);
        }
    }

    /** The grid state, but only if the callback came from the grid message it describes. */
    private GridState currentGrid(CallbackQuery cb) {
        GridState state = gridByChat.get(cb.getMessage().getChatId());
        return state != null && Objects.equals(state.messageId(), cb.getMessage().getMessageId()) ? state : null;
    }

    // ---- favorites (star mode) ----

    private void handleStarModeOn(CallbackQuery cb) {
        if (!registration.isApproved(cb.getFrom().getId())) {
            answerCallback(cb.getId(), "Нужен доступ.");
            return;
        }
        GridState state = currentGrid(cb);
        if (state == null) {
            answerCallback(cb.getId(), "Список устарел. Нажми «Обновить».");
            return;
        }
        answerCallback(cb.getId());
        if (!state.starMode()) { // a double tap would be an unchanged edit, which Telegram rejects
            showGrid(cb.getMessage().getChatId(), state.withStarMode(true), cb.getFrom().getId());
        }
    }

    private void handleStarModeDone(CallbackQuery cb, String data) {
        if (!registration.isApproved(cb.getFrom().getId())) {
            answerCallback(cb.getId(), "Нужен доступ.");
            return;
        }
        long chatId = cb.getMessage().getChatId();
        GridState state = currentGrid(cb);
        if (state != null) {
            answerCallback(cb.getId());
            if (state.starMode()) {
                showGrid(chatId, state.withStarMode(false), cb.getFrom().getId());
            }
            return;
        }
        // State lost (e.g. bot restarted): there is no Refresh button in star mode, so refresh from the
        // coordinates carried in the callback instead of leaving the user stuck.
        double[] point = parsePoint(data.substring(CB_GRID_STAR_DONE.length()));
        if (point == null) {
            answerCallback(cb.getId(), "Список устарел. Запроси АЗС заново: /nearby");
            return;
        }
        try {
            editGrid(chatId, cb.getMessage().getMessageId(), point[0], point[1], cb.getFrom().getId());
            answerCallback(cb.getId(), "Обновлено");
        } catch (GdeBenzApiClient.ApiException e) {
            answerCallback(cb.getId(), "Источник недоступен, попробуй позже.");
        }
    }

    private void handleGridStar(CallbackQuery cb, String data) {
        long userId = cb.getFrom().getId();
        if (!registration.isApproved(userId)) {
            answerCallback(cb.getId(), "Нужен доступ.");
            return;
        }
        GridState state = currentGrid(cb);
        int index = parseIndex(data.substring(CB_GRID_STAR.length()));
        if (state == null || index < 0 || index >= Math.min(state.result().stations().size(), MAX_ROWS)) {
            answerCallback(cb.getId(), "Список устарел. Нажми «Готово».");
            return;
        }
        StationView s = state.result().stations().get(index);
        switch (favorites.toggle(userId, s)) {
            case ADDED -> answerCallback(cb.getId(), "⭐ " + stationLabel(s) + " — в избранном");
            case REMOVED -> answerCallback(cb.getId(), "Убрано из избранного: " + stationLabel(s));
            case LIMIT_REACHED -> {
                answerCallback(cb.getId(), "Максимум " + FavoriteService.MAX_FAVORITES
                        + " избранных — сначала убери лишнюю ★");
                return;
            }
        }
        showGrid(cb.getMessage().getChatId(), state, userId);
    }

    /** "Brand, address" for toasts, kept short (a toast is capped at 200 chars). */
    private static String stationLabel(StationView s) {
        String label = s.brand() == null ? "" : s.brand();
        if (s.addr() != null && !s.addr().isBlank()) {
            label = label.isEmpty() ? s.addr() : label + ", " + s.addr();
        }
        return label.length() > 80 ? label.substring(0, 79) + "…" : label;
    }

    private void handleGridRefresh(CallbackQuery cb, String data) {
        double[] point = parsePoint(data.substring(CB_GRID_REFRESH.length()));
        if (point == null) {
            answerCallback(cb.getId(), "Некорректные данные.");
            return;
        }
        if (!registration.isApproved(cb.getFrom().getId())) {
            answerCallback(cb.getId(), "Нужен доступ.");
            return;
        }
        try {
            editGrid(cb.getMessage().getChatId(), cb.getMessage().getMessageId(),
                    point[0], point[1], cb.getFrom().getId());
            answerCallback(cb.getId(), "Обновлено");
        } catch (GdeBenzApiClient.ApiException e) {
            answerCallback(cb.getId(), "Источник недоступен, попробуй позже.");
        }
    }

    private void handleGridInfo(CallbackQuery cb, String data) {
        StationView s = stationAt(cb.getMessage().getChatId(), parseIndex(data.substring(CB_GRID_INFO.length())));
        if (s == null) {
            answerCallback(cb.getId(), "Список устарел. Нажми «Обновить».");
            return;
        }
        answerAlert(cb.getId(), infoText(s, favorites.ids(cb.getFrom().getId()).contains(s.osmId())));
    }

    private void handleGridFuel(CallbackQuery cb, String data) {
        String[] parts = data.substring(CB_GRID_FUEL.length()).split(":");
        if (parts.length != 2) {
            answerCallback(cb.getId(), null);
            return;
        }
        long chatId = cb.getMessage().getChatId();
        int gradeIdx = parseIndex(parts[1]);
        StationView s = stationAt(chatId, parseIndex(parts[0]));
        GridState state = gridByChat.get(chatId);
        if (s == null || state == null || gradeIdx < 0 || gradeIdx >= GRADES.length) {
            answerCallback(cb.getId(), "Список устарел. Нажми «Обновить».");
            return;
        }
        String grade = GRADES[gradeIdx];
        answerAlert(cb.getId(), NearbyFormatter.fuelPopup(s, grade, state.tbank().match(s.lat(), s.lon(), s.brand())));
    }

    private void handleGridComments(CallbackQuery cb, String data) {
        long chatId = cb.getMessage().getChatId();
        StationView s = stationAt(chatId, parseIndex(data.substring(CB_GRID_CMT.length())));
        GridState state = gridByChat.get(chatId);
        if (s == null || state == null) {
            answerCallback(cb.getId(), "Список устарел. Нажми «Обновить».");
            return;
        }
        openComments(cb, s, state.tbank());
    }

    private void openComments(CallbackQuery cb, StationView s, TbankSnapshot snapshot) {
        sendComments(cb, s.osmId(), snapshot.match(s.lat(), s.lon(), s.brand()));
    }

    /** "💬 Отметки" under a favorite alert: the station's recent reports (no T-Bank lookup). */
    private void handleWatchComments(CallbackQuery cb, String data) {
        sendComments(cb, data.substring(FavoriteWatcher.CB_COMMENTS.length()), Optional.empty());
    }

    private void sendComments(CallbackQuery cb, String osmId, Optional<TbankStation> match) {
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
        if (match.isPresent()) {
            text = text + "\n\n" + formatter.formatTbankPopup(match);
        }
        answerCallback(cb.getId());
        send(SendMessage.builder()
                .chatId(cb.getMessage().getChatId())
                .text(text)
                .parseMode("HTML")
                .replyMarkup(InlineKeyboardMarkup.builder().keyboardRow(rowOf(btn("✖ Закрыть", CB_COMMENTS_CLOSE))).build())
                .build());
    }

    // ---- filters ----

    private void onFilters(User from, long chatId) {
        if (!registration.isApproved(from.getId())) {
            sendText(chatId, "Нужен доступ. Отправь /register, затем дождись одобрения.");
            return;
        }
        sendFilterMessage(chatId, filters.get(from.getId()));
    }

    private void handleFilterToggle(CallbackQuery cb, String data) {
        if (!registration.isApproved(cb.getFrom().getId())) {
            answerCallback(cb.getId(), "Нужен доступ.");
            return;
        }
        Set<String> active = filters.toggle(cb.getFrom().getId(), data.substring(CB_FILTER_TOGGLE.length()));
        answerCallback(cb.getId());
        editFilterMessage(cb, active);
    }

    private void handleFilterClear(CallbackQuery cb) {
        if (!registration.isApproved(cb.getFrom().getId())) {
            answerCallback(cb.getId(), "Нужен доступ.");
            return;
        }
        filters.clear(cb.getFrom().getId());
        answerCallback(cb.getId(), "Сброшено");
        editFilterMessage(cb, filters.get(cb.getFrom().getId()));
    }

    private void handleFilterClose(CallbackQuery cb) {
        long chatId = cb.getMessage().getChatId();
        answerCallback(cb.getId());
        deleteMessage(chatId, cb.getMessage().getMessageId());
        GridState state = gridByChat.get(chatId);
        if (state != null && state.messageId() != null) {
            try {
                editGrid(chatId, state.messageId(), state.lat(), state.lon(), cb.getFrom().getId());
            } catch (GdeBenzApiClient.ApiException e) {
                log.warn("Failed to refresh grid after filter change", e);
            }
        }
    }

    private void sendFilterMessage(long chatId, Set<String> active) {
        send(SendMessage.builder()
                .chatId(chatId)
                .text(filterText(active))
                .parseMode("HTML")
                .replyMarkup(filterKeyboard(active))
                .build());
    }

    private void editFilterMessage(CallbackQuery cb, Set<String> active) {
        try {
            client.execute(EditMessageText.builder()
                    .chatId(String.valueOf(cb.getMessage().getChatId()))
                    .messageId(cb.getMessage().getMessageId())
                    .text(filterText(active))
                    .parseMode("HTML")
                    .replyMarkup(filterKeyboard(active))
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to edit filter message", e);
        }
    }

    private String filterText(Set<String> active) {
        if (active.isEmpty()) {
            return "⚙ <b>Фильтр по топливу</b>\n"
                    + "Выбери нужные виды — список будет показывать только такие АЗС.\n"
                    + "Сейчас: без фильтра (показываю все).";
        }
        return "⚙ <b>Фильтр по топливу</b>\n"
                + "Показываю АЗС, где есть хотя бы один из выбранных.\n"
                + "Сейчас: " + String.join(", ", active);
    }

    private InlineKeyboardMarkup filterKeyboard(Set<String> active) {
        InlineKeyboardRow grades = new InlineKeyboardRow();
        for (String grade : FuelFilterService.GRADES) {
            grades.add(btn(active.contains(grade) ? "✅ " + grade : grade, CB_FILTER_TOGGLE + grade));
        }
        InlineKeyboardRow controls = new InlineKeyboardRow();
        controls.add(btn("♻️ Сбросить", CB_FILTER_CLEAR));
        controls.add(btn("✖ Закрыть", CB_FILTER_CLOSE));
        return InlineKeyboardMarkup.builder().keyboardRow(grades).keyboardRow(controls).build();
    }

    // ---- settings (favorite alerts + quiet hours) ----

    private void onSettings(User from, long chatId) {
        if (!registration.isApproved(from.getId())) {
            sendText(chatId, "Нужен доступ. Отправь /register, затем дождись одобрения.");
            return;
        }
        NotificationSettingsService.Settings settings = notifications.get(from.getId());
        send(SendMessage.builder()
                .chatId(chatId)
                .text(settingsText(settings))
                .parseMode("HTML")
                .replyMarkup(settingsKeyboard(settings))
                .build());
    }

    private void handleSettings(CallbackQuery cb, String data) {
        long userId = cb.getFrom().getId();
        if (!registration.isApproved(userId)) {
            answerCallback(cb.getId(), "Нужен доступ.");
            return;
        }
        if (data.equals(CB_SET_CLOSE)) {
            answerCallback(cb.getId());
            deleteMessage(cb.getMessage().getChatId(), cb.getMessage().getMessageId());
            return;
        }
        NotificationSettingsService.Settings settings;
        if (data.equals(CB_SET_NOTIFY)) {
            settings = notifications.toggleEnabled(userId);
        } else if (data.equals(CB_SET_QUIET)) {
            settings = notifications.toggleQuiet(userId);
        } else if (data.startsWith(CB_SET_FROM)) {
            settings = notifications.shiftQuietFrom(userId, parseDelta(data.substring(CB_SET_FROM.length())));
        } else if (data.startsWith(CB_SET_TO)) {
            settings = notifications.shiftQuietTo(userId, parseDelta(data.substring(CB_SET_TO.length())));
        } else {
            answerCallback(cb.getId(), "Неизвестное действие.");
            return;
        }
        answerCallback(cb.getId());
        try {
            client.execute(EditMessageText.builder()
                    .chatId(String.valueOf(cb.getMessage().getChatId()))
                    .messageId(cb.getMessage().getMessageId())
                    .text(settingsText(settings))
                    .parseMode("HTML")
                    .replyMarkup(settingsKeyboard(settings))
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to edit settings message", e);
        }
    }

    private static int parseDelta(String raw) {
        return "-".equals(raw) ? -1 : 1;
    }

    private String settingsText(NotificationSettingsService.Settings s) {
        StringBuilder sb = new StringBuilder("⚙️ <b>Настройки</b>\n\n");
        sb.append("🔔 Оповещения по избранным: ").append(s.enabled() ? "вкл" : "выкл").append('\n');
        sb.append("🌙 Тихие часы: ")
                .append(s.quietEnabled() ? hour(s.quietFrom()) + "–" + hour(s.quietTo()) + " МСК" : "выкл");
        sb.append("\n\nПишу, когда на избранной АЗС меняется наличие или набор топлива.");
        if (s.quietEnabled()) {
            sb.append(" В тихие часы — без звука.");
        }
        return sb.toString();
    }

    private InlineKeyboardMarkup settingsKeyboard(NotificationSettingsService.Settings s) {
        var builder = InlineKeyboardMarkup.builder()
                .keyboardRow(rowOf(btn("🔔 Оповещения: " + (s.enabled() ? "вкл" : "выкл"), CB_SET_NOTIFY)))
                .keyboardRow(rowOf(btn("🌙 Тихие часы: " + (s.quietEnabled() ? "вкл" : "выкл"), CB_SET_QUIET)));
        if (s.quietEnabled()) {
            builder.keyboardRow(stepRow("с " + hour(s.quietFrom()), CB_SET_FROM));
            builder.keyboardRow(stepRow("до " + hour(s.quietTo()), CB_SET_TO));
        }
        return builder.keyboardRow(rowOf(btn("✖ Закрыть", CB_SET_CLOSE))).build();
    }

    private InlineKeyboardRow stepRow(String label, String prefix) {
        InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(btn("◀", prefix + "-"));
        row.add(btn(label, CB_GRID_NOOP));
        row.add(btn("▶", prefix + "+"));
        return row;
    }

    private static String hour(int h) {
        return String.format(Locale.ROOT, "%02d:00", h);
    }

    // ---- grid building ----

    private String gridTitle(GridState state, Set<String> favIds) {
        NearbyResult result = state.result();
        Set<String> filter = state.filter();
        boolean filtered = filter != null && !filter.isEmpty();
        if (result.stations().isEmpty()) {
            if (filtered) {
                return "По фильтру (" + String.join(", ", filter)
                        + ") рядом ничего не нашлось. Поменяй его в ⚙ Фильтры.";
            }
            return "Рядом ничего не нашлось. Попробуй ещё раз позже.";
        }
        String title = state.starMode()
                ? "⭐ <b>Отметь свои АЗС</b> · " + favIds.size() + "/" + FavoriteService.MAX_FAVORITES
                        + "\nНажми ☆ — добавить в избранное, ★ — убрать"
                : "⛽ <b>АЗС рядом</b> — нажми на название или марку для деталей";
        if (filtered) {
            title += "\n🔎 фильтр: " + String.join(", ", filter);
        }
        return title;
    }

    private InlineKeyboardMarkup gridKeyboard(GridState state, Set<String> favIds) {
        double lat = state.lat();
        double lon = state.lon();
        NearbyResult result = state.result();
        boolean starMode = state.starMode();
        var builder = InlineKeyboardMarkup.builder();
        if (result.stations().isEmpty()) {
            builder.keyboardRow(rowOf(btn("🔄 Обновить", CB_GRID_REFRESH + point(lat, lon))));
            return builder.build();
        }

        InlineKeyboardRow header = new InlineKeyboardRow();
        header.add(btn("АЗС", CB_GRID_NOOP));
        for (String grade : GRADES) {
            header.add(btn(grade, CB_GRID_NOOP));
        }
        header.add(btn("🗺", CB_GRID_NOOP));
        header.add(btn(starMode ? "⭐" : "💬", CB_GRID_NOOP));
        builder.keyboardRow(header);

        int limit = Math.min(result.stations().size(), MAX_ROWS);
        for (int i = 0; i < limit; i++) {
            StationView s = result.stations().get(i);
            boolean fav = favIds.contains(s.osmId());
            Optional<TbankStation> tb = state.tbank().match(s.lat(), s.lon(), s.brand());
            InlineKeyboardRow row = new InlineKeyboardRow();
            row.add(btn(nameCell(s, fav), CB_GRID_INFO + i));
            for (int g = 0; g < GRADES.length; g++) {
                row.add(btn(NearbyFormatter.fuelCell(s, GRADES[g], tb), CB_GRID_FUEL + i + ":" + g));
            }
            row.add(urlBtn("🗺", NearbyFormatter.routeUrl(lat, lon, s.lat(), s.lon())));
            row.add(starMode ? btn(fav ? "★" : "☆", CB_GRID_STAR + i) : btn("💬", CB_GRID_CMT + i));
            builder.keyboardRow(row);
        }

        if (starMode) {
            builder.keyboardRow(rowOf(btn("✓ Готово", CB_GRID_STAR_DONE + point(lat, lon))));
        } else {
            InlineKeyboardRow controls = new InlineKeyboardRow();
            controls.add(btn("🔄 Обновить", CB_GRID_REFRESH + point(lat, lon)));
            controls.add(btn("⭐ Отметить", CB_GRID_STAR_MODE));
            builder.keyboardRow(controls);
        }
        return builder.build();
    }

    /** Brand, shortened to fit the cell; favorites get a ★ prefix (which eats one char of the budget). */
    private String nameCell(StationView s, boolean favorite) {
        String name = s.brand() == null ? "" : s.brand();
        int max = favorite ? NAME_MAX - 1 : NAME_MAX;
        if (name.length() > max) {
            name = name.substring(0, max - 1) + "…";
        }
        return favorite ? "★" + name : name;
    }

    private String infoText(StationView s, boolean favorite) {
        StringBuilder sb = new StringBuilder(favorite ? "★ " : "").append(s.brand() == null ? "" : s.brand());
        if (!s.addr().isBlank()) {
            sb.append('\n').append(s.addr());
        }
        sb.append('\n').append(String.format(Locale.ROOT, "%.1f", s.distanceKm())).append(" км")
                .append(" · уверенность ").append(Math.round(s.confidence() * 100)).append('%');
        if (!s.detail().isBlank()) {
            sb.append('\n').append(s.detail());
        } else if (!s.fuelsNow().isBlank()) {
            sb.append("\nСейчас: ").append(s.fuelsNow().replace(",", ", "));
        }
        String text = sb.toString();
        return text.length() > 200 ? text.substring(0, 199) : text;
    }

    private StationView stationAt(long chatId, int index) {
        GridState state = gridByChat.get(chatId);
        if (state == null || index < 0) {
            return null;
        }
        List<StationView> stations = state.result().stations();
        return index < stations.size() ? stations.get(index) : null;
    }

    private static int parseIndex(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
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

    private static String point(double lat, double lon) {
        return String.format(Locale.ROOT, "%.5f:%.5f", lat, lon);
    }

    // ---- callbacks ----

    private void handleCallback(CallbackQuery cb) {
        String data = cb.getData() == null ? "" : cb.getData();
        if (data.startsWith(CB_APPROVE) || data.startsWith(CB_REJECT)) {
            handleRegistrationDecision(cb, data);
        } else if (data.equals(CB_NEARBY)) {
            onNearbyCallback(cb);
        } else if (data.equals(CB_GRID_NOOP)) {
            answerCallback(cb.getId());
        } else if (data.equals(CB_GRID_STAR_MODE)) {
            handleStarModeOn(cb);
        } else if (data.startsWith(CB_GRID_STAR_DONE)) {
            handleStarModeDone(cb, data);
        } else if (data.startsWith(CB_GRID_STAR)) {
            handleGridStar(cb, data);
        } else if (data.startsWith(CB_GRID_REFRESH)) {
            handleGridRefresh(cb, data);
        } else if (data.startsWith(CB_GRID_INFO)) {
            handleGridInfo(cb, data);
        } else if (data.startsWith(CB_GRID_FUEL)) {
            handleGridFuel(cb, data);
        } else if (data.startsWith(CB_GRID_CMT)) {
            handleGridComments(cb, data);
        } else if (data.equals(CB_COMMENTS_CLOSE)) {
            answerCallback(cb.getId());
            deleteMessage(cb.getMessage().getChatId(), cb.getMessage().getMessageId());
        } else if (data.startsWith(CB_FILTER_TOGGLE)) {
            handleFilterToggle(cb, data);
        } else if (data.equals(CB_FILTER_CLEAR)) {
            handleFilterClear(cb);
        } else if (data.equals(CB_FILTER_CLOSE)) {
            handleFilterClose(cb);
        } else if (data.startsWith("set:")) {
            handleSettings(cb, data);
        } else if (data.startsWith(FavoriteWatcher.CB_COMMENTS)) {
            handleWatchComments(cb, data);
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
        row.add(btn("✅ Одобрить", CB_APPROVE + requester.getUserId()));
        row.add(btn("🚫 Отклонить", CB_REJECT + requester.getUserId()));
        send(SendMessage.builder()
                .chatId(adminChatId)
                .text("🆕 Запрос доступа: " + display(requester))
                .replyMarkup(InlineKeyboardMarkup.builder().keyboardRow(row).build())
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
                BotCommand.builder().command("nearby").description("Показать ближайшие АЗС").build(),
                BotCommand.builder().command("filters").description("Фильтр по топливу").build(),
                BotCommand.builder().command("settings").description("Настройки оповещений").build(),
                BotCommand.builder().command("help").description("Помощь").build()));
    }

    private void setAdminMenu(long chatId) {
        setMenu(chatId, List.of(
                BotCommand.builder().command("nearby").description("Показать ближайшие АЗС").build(),
                BotCommand.builder().command("filters").description("Фильтр по топливу").build(),
                BotCommand.builder().command("settings").description("Настройки оповещений").build(),
                BotCommand.builder().command("pending").description("Заявки на доступ").build(),
                BotCommand.builder().command("help").description("Помощь").build()));
    }

    /** Re-applies the per-chat command menu so renamed commands refresh on next interaction. */
    private void applyMenu(long userId, long chatId) {
        if (registration.isAdmin(userId)) {
            setAdminMenu(chatId);
        } else if (registration.isApproved(userId)) {
            setApprovedMenu(chatId);
        }
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

    private InlineKeyboardButton btn(String text, String callbackData) {
        return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
    }

    private InlineKeyboardButton urlBtn(String text, String url) {
        return InlineKeyboardButton.builder().text(text).url(url).build();
    }

    private InlineKeyboardRow rowOf(InlineKeyboardButton button) {
        InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(button);
        return row;
    }

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

    private Integer sendReturningId(SendMessage message) {
        try {
            var sent = client.execute(message);
            return sent == null ? null : sent.getMessageId();
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chat {}", message.getChatId(), e);
            return null;
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

    private void answerAlert(String callbackId, String text) {
        try {
            client.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId)
                    .text(text)
                    .showAlert(true)
                    .build());
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

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
import ru.gdebenz.user.FuelFilterService;
import ru.gdebenz.user.RegistrationService;
import ru.gdebenz.user.TgUser;

import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final String CB_COMMENTS_CLOSE = "cmt:close";
    private static final String CB_FILTERS_OPEN = "flt:open";
    private static final String CB_FILTER_TOGGLE = "flt:t:";
    private static final String CB_FILTER_CLEAR = "flt:clear";
    private static final String CB_FILTER_CLOSE = "flt:close";

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

    /** Per-chat id of the "share your location" prompt, so we can delete it afterwards. */
    private final Map<Long, Integer> promptByChat = new ConcurrentHashMap<>();
    /** Per-chat current grid state so callbacks can resolve a station by its row index. */
    private final Map<Long, GridState> gridByChat = new ConcurrentHashMap<>();

    private record GridState(double lat, double lon, NearbyResult result, TbankSnapshot tbank, Integer messageId) {
    }

    public GdeBenzBot(TelegramBotProperties properties, TelegramClient client,
                      RegistrationService registration, FuelAvailabilityService fuel,
                      TbankService tbank, NearbyFormatter formatter, FuelFilterService filters) {
        this.botToken = properties.token();
        this.client = client;
        this.registration = registration;
        this.fuel = fuel;
        this.tbank = tbank;
        this.formatter = formatter;
        this.filters = filters;
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
        renderGrid(chatId, lat, lon, FuelAvailabilityService.filterByFuels(result, filter), filter);
    }

    /** Sends the grid as a single message, replacing the previous one. */
    private void renderGrid(long chatId, double lat, double lon, NearbyResult result, Set<String> filter) {
        GridState old = gridByChat.remove(chatId);
        if (old != null && old.messageId() != null) {
            deleteMessage(chatId, old.messageId());
        }
        TbankSnapshot snapshot = tbank.snapshot(lat, lon);
        Integer id = sendReturningId(SendMessage.builder()
                .chatId(chatId)
                .text(gridTitle(result, filter))
                .parseMode("HTML")
                .replyMarkup(gridKeyboard(lat, lon, result, snapshot))
                .build());
        gridByChat.put(chatId, new GridState(lat, lon, result, snapshot, id));
    }

    /** Re-fetches nearby, applies the user's filter, and edits the existing grid message in place. */
    private void editGrid(long chatId, int messageId, double lat, double lon, long userId) {
        Set<String> filter = filters.get(userId);
        NearbyResult result = FuelAvailabilityService.filterByFuels(fuel.nearby(lat, lon), filter);
        TbankSnapshot snapshot = tbank.snapshot(lat, lon);
        try {
            client.execute(EditMessageText.builder()
                    .chatId(String.valueOf(chatId))
                    .messageId(messageId)
                    .text(gridTitle(result, filter))
                    .parseMode("HTML")
                    .replyMarkup(gridKeyboard(lat, lon, result, snapshot))
                    .build());
            gridByChat.put(chatId, new GridState(lat, lon, result, snapshot, messageId));
        } catch (TelegramApiException e) {
            log.error("Failed to edit grid", e);
        }
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
        answerAlert(cb.getId(), infoText(s));
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
        if (!registration.isApproved(cb.getFrom().getId())) {
            answerCallback(cb.getId(), "Нужен доступ.");
            return;
        }
        String text;
        try {
            text = formatter.formatComments(fuel.comments(s.osmId()));
        } catch (GdeBenzApiClient.ApiException e) {
            answerCallback(cb.getId(), "Не удалось загрузить отметки.");
            return;
        }
        Optional<TbankStation> match = snapshot.match(s.lat(), s.lon(), s.brand());
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

    private void handleFiltersOpen(CallbackQuery cb) {
        answerCallback(cb.getId());
        if (!registration.isApproved(cb.getFrom().getId())) {
            sendText(cb.getMessage().getChatId(), "Нужен доступ. Отправь /register.");
            return;
        }
        sendFilterMessage(cb.getMessage().getChatId(), filters.get(cb.getFrom().getId()));
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

    // ---- grid building ----

    private String gridTitle(NearbyResult result, Set<String> filter) {
        boolean filtered = filter != null && !filter.isEmpty();
        if (result.stations().isEmpty()) {
            if (filtered) {
                return "По фильтру (" + String.join(", ", filter)
                        + ") рядом ничего не нашлось. Поменяй его в ⚙ Фильтры.";
            }
            return "Рядом ничего не нашлось. Попробуй ещё раз позже.";
        }
        String title = "⛽ <b>АЗС рядом</b> — нажми на название или марку для деталей";
        if (filtered) {
            title += "\n🔎 фильтр: " + String.join(", ", filter);
        }
        return title;
    }

    private InlineKeyboardMarkup gridKeyboard(double lat, double lon, NearbyResult result, TbankSnapshot snapshot) {
        var builder = InlineKeyboardMarkup.builder();
        if (result.stations().isEmpty()) {
            builder.keyboardRow(actionsRow(lat, lon));
            return builder.build();
        }

        InlineKeyboardRow header = new InlineKeyboardRow();
        header.add(btn("АЗС", CB_GRID_NOOP));
        for (String grade : GRADES) {
            header.add(btn(grade, CB_GRID_NOOP));
        }
        header.add(btn("🗺", CB_GRID_NOOP));
        header.add(btn("💬", CB_GRID_NOOP));
        builder.keyboardRow(header);

        int limit = Math.min(result.stations().size(), MAX_ROWS);
        for (int i = 0; i < limit; i++) {
            StationView s = result.stations().get(i);
            Optional<TbankStation> tb = snapshot.match(s.lat(), s.lon(), s.brand());
            InlineKeyboardRow row = new InlineKeyboardRow();
            row.add(btn(nameCell(s), CB_GRID_INFO + i));
            for (int g = 0; g < GRADES.length; g++) {
                row.add(btn(NearbyFormatter.fuelCell(s, GRADES[g], tb), CB_GRID_FUEL + i + ":" + g));
            }
            row.add(urlBtn("🗺", NearbyFormatter.routeUrl(lat, lon, s.lat(), s.lon())));
            row.add(btn("💬", CB_GRID_CMT + i));
            builder.keyboardRow(row);
        }

        builder.keyboardRow(actionsRow(lat, lon));
        return builder.build();
    }

    private InlineKeyboardRow actionsRow(double lat, double lon) {
        InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(btn("🔄 Обновить", CB_GRID_REFRESH + point(lat, lon)));
        row.add(btn("⚙ Фильтры", CB_FILTERS_OPEN));
        return row;
    }

    private String nameCell(StationView s) {
        String name = s.brand() == null ? "" : s.brand();
        if (name.length() > NAME_MAX) {
            name = name.substring(0, NAME_MAX - 1) + "…";
        }
        return name;
    }

    private String infoText(StationView s) {
        StringBuilder sb = new StringBuilder(s.brand() == null ? "" : s.brand());
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
        } else if (data.equals(CB_FILTERS_OPEN)) {
            handleFiltersOpen(cb);
        } else if (data.startsWith(CB_FILTER_TOGGLE)) {
            handleFilterToggle(cb, data);
        } else if (data.equals(CB_FILTER_CLEAR)) {
            handleFilterClear(cb);
        } else if (data.equals(CB_FILTER_CLOSE)) {
            handleFilterClose(cb);
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
                BotCommand.builder().command("help").description("Помощь").build()));
    }

    private void setAdminMenu(long chatId) {
        setMenu(chatId, List.of(
                BotCommand.builder().command("nearby").description("Показать ближайшие АЗС").build(),
                BotCommand.builder().command("filters").description("Фильтр по топливу").build(),
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

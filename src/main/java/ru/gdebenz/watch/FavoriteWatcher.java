package ru.gdebenz.watch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.gdebenz.config.WatchProperties;
import ru.gdebenz.fuel.CommentView;
import ru.gdebenz.fuel.FuelAvailabilityService;
import ru.gdebenz.fuel.FuelStatus;
import ru.gdebenz.fuel.GdeBenzApiClient;
import ru.gdebenz.fuel.api.ChatMessageDto;
import ru.gdebenz.fuel.api.StationSummaryDto;
import ru.gdebenz.user.BotUser;
import ru.gdebenz.user.BotUserRepository;
import ru.gdebenz.user.FavoriteStation;
import ru.gdebenz.user.FavoriteStationRepository;
import ru.gdebenz.user.FuelFilterService;
import ru.gdebenz.user.NotificationSettingsService.Settings;
import ru.gdebenz.user.RegistrationStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Polls every favorite station (once per station, however many users starred it) and alerts its
 * subscribers. Two independent loops (run one at a time by the default single-threaded scheduler):
 * <ul>
 *   <li>status, every {@code interval}: alert when the essence — status or grade set — changes. With
 *   "only by filter" on, a user is alerted only about grades from their /filters selection;</li>
 *   <li>station chat, every {@code chatInterval}: forward new messages with a keyword ("бензовоз",
 *   "привезли", ...) in a non-question sentence.</li>
 * </ul>
 * Only approved users with alerts enabled count; during their quiet hours alerts are sent silently.
 * The last seen state lives in {@code station_watch}, so restarts don't re-send alerts, and the first
 * observation of a station (or its chat) only records a baseline.
 */
@Component
@ConditionalOnProperty(name = "gdebenz.watch.enabled", havingValue = "true")
public class FavoriteWatcher {

    /** Callback prefix for the "reports" button under an alert; handled by the bot. */
    public static final String CB_COMMENTS = "w:c:";

    private static final Logger log = LoggerFactory.getLogger(FavoriteWatcher.class);

    private final GdeBenzApiClient api;
    private final FavoriteStationRepository favorites;
    private final BotUserRepository users;
    private final StationWatchRepository watches;
    private final TelegramClient telegram;
    private final WatchProperties props;
    private final Clock clock;

    public FavoriteWatcher(GdeBenzApiClient api, FavoriteStationRepository favorites, BotUserRepository users,
                           StationWatchRepository watches, TelegramClient telegram, WatchProperties props,
                           Clock clock) {
        this.api = api;
        this.favorites = favorites;
        this.users = users;
        this.watches = watches;
        this.telegram = telegram;
        this.props = props;
        this.clock = clock;
    }

    private record Subscriber(BotUser user, FavoriteStation favorite) {
    }

    @Scheduled(fixedDelayString = "${gdebenz.watch.interval:60s}", initialDelayString = "${gdebenz.watch.interval:60s}")
    public void poll() {
        forEachStation(u -> true, this::checkStatus);
    }

    @Scheduled(fixedDelayString = "${gdebenz.watch.chat-interval:10m}", initialDelayString = "${gdebenz.watch.interval:60s}")
    public void pollChats() {
        forEachStation(BotUser::isNotifyChat, this::checkChat);
    }

    /** Runs {@code check} once per favorite station that has at least one active subscriber. */
    private void forEachStation(Predicate<BotUser> wants, BiConsumer<String, List<Subscriber>> check) {
        List<FavoriteStation> all = favorites.findAll();
        if (all.isEmpty()) {
            return;
        }
        Map<Long, BotUser> active = users.findAllById(all.stream().map(FavoriteStation::getUserId).distinct().toList())
                .stream()
                .filter(u -> u.getStatus() == RegistrationStatus.APPROVED && u.isNotifyEnabled() && wants.test(u))
                .collect(Collectors.toMap(BotUser::getUserId, Function.identity()));

        Map<String, List<Subscriber>> byStation = all.stream()
                .filter(f -> active.containsKey(f.getUserId()))
                .collect(Collectors.groupingBy(FavoriteStation::getOsmId, LinkedHashMap::new,
                        Collectors.mapping(f -> new Subscriber(active.get(f.getUserId()), f), Collectors.toList())));

        boolean first = true;
        for (Map.Entry<String, List<Subscriber>> entry : byStation.entrySet()) {
            if (!first && !pause()) {
                return;
            }
            first = false;
            try {
                check.accept(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.warn("Failed to check favorite station {}: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    // ---- status ----

    private void checkStatus(String osmId, List<Subscriber> subscribers) {
        StationSummaryDto summary = api.station(osmId);
        Instant updated = FuelAvailabilityService.parseTimestamp(summary.updated());
        Instant now = clock.instant();
        StationWatch watch = watches.findById(osmId).orElse(null);
        boolean baseline = watch == null || watch.getStatus() == null; // the chat loop may create the row first

        if (!baseline && (updated == null
                || (watch.getLastUpdated() != null && !updated.isAfter(watch.getLastUpdated())))) {
            watch.touch(now);
            watches.save(watch);
            return; // no new reports
        }

        List<CommentView> comments = FuelAvailabilityService.mapComments(api.comments(osmId, props.commentsLimit()));
        WatchLogic.Essence essence = WatchLogic.essence(FuelStatus.from(summary.status()), summary.fuelsNow(), comments);

        if (watch == null) {
            watches.save(new StationWatch(osmId, updated, essence.status().name(), essence.fuels(), now));
            return; // first sight: baseline only
        }
        WatchLogic.Essence was = new WatchLogic.Essence(statusOf(watch.getStatus()), nullToEmpty(watch.getFuels()));
        watch.update(updated, essence.status().name(), essence.fuels(), now);
        watches.save(watch);

        if (!baseline && !was.equals(essence)) {
            log.info("Favorite station {} changed: {} -> {}", osmId, was, essence);
            CommentView latest = comments.isEmpty() ? null : comments.getFirst();
            for (Subscriber sub : subscribers) {
                Settings settings = Settings.of(sub.user());
                Set<String> filter = settings.useFilter()
                        ? FuelFilterService.parse(sub.user().getFuelFilter()) : Set.of();
                List<String> changes = WatchLogic.changeLines(was, essence, filter);
                if (!changes.isEmpty()) { // else nothing this user watches has changed
                    FavoriteStation fav = sub.favorite();
                    send(sub, osmId, WatchLogic.alertText(fav.getBrand(), fav.getAddr(), changes, essence, latest), now);
                }
            }
        }
    }

    // ---- station chat ----

    private void checkChat(String osmId, List<Subscriber> subscribers) {
        List<ChatMessageDto> messages = api.chat(osmId, props.chatLimit());
        Instant now = clock.instant();
        StationWatch watch = watches.findById(osmId).orElseGet(() -> new StationWatch(osmId, null, null, null, now));
        long newest = messages.stream().mapToLong(ChatMessageDto::id).max().orElse(0L);
        Long seen = watch.getLastChatId();

        if (seen != null) {
            List<ChatMessageDto> fresh = messages.stream()
                    .filter(m -> m.id() > seen)
                    .sorted(Comparator.comparingLong(ChatMessageDto::id))
                    .toList();
            for (ChatMessageDto m : fresh) {
                if (WatchLogic.hasKeywordStatement(m.body(), props.keywords())) {
                    log.info("Keyword message in chat of favorite station {}: id {}", osmId, m.id());
                    for (Subscriber sub : subscribers) {
                        FavoriteStation fav = sub.favorite();
                        send(sub, osmId, WatchLogic.chatAlertText(fav.getBrand(), fav.getAddr(), m), now);
                    }
                }
            }
        }
        // first sight records a baseline; never move backwards if the API returns fewer messages
        watch.setLastChatId(seen == null ? newest : Math.max(seen, newest));
        watches.save(watch);
    }

    // ---- sending ----

    private void send(Subscriber sub, String osmId, String html, Instant at) {
        BotUser user = sub.user();
        FavoriteStation fav = sub.favorite();
        InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(InlineKeyboardButton.builder().text("🗺 Маршрут").url(WatchLogic.routeUrl(fav.getLat(), fav.getLon())).build());
        row.add(InlineKeyboardButton.builder().text("💬 Отметки").callbackData(CB_COMMENTS + osmId).build());
        try {
            telegram.execute(SendMessage.builder()
                    .chatId(user.getChatId())
                    .text(html)
                    .parseMode("HTML")
                    .disableNotification(Settings.of(user).isQuiet(WatchLogic.hourMsk(at)))
                    .replyMarkup(InlineKeyboardMarkup.builder().keyboardRow(row).build())
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Failed to send favorite alert to chat {}", user.getChatId(), e);
        }
    }

    /** Sleeps between station requests; returns false if interrupted (shutdown). */
    private boolean pause() {
        try {
            Thread.sleep(props.requestPause().toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static FuelStatus statusOf(String name) {
        try {
            return name == null ? FuelStatus.UNKNOWN : FuelStatus.valueOf(name);
        } catch (IllegalArgumentException e) {
            return FuelStatus.UNKNOWN;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

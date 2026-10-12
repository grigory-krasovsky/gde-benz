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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Polls every favorite station (once per station, however many users starred it) and alerts its
 * subscribers when the station's essence — status or grade set — changes. Only stations of approved
 * users with alerts enabled are polled. With "only by filter" on, a user is alerted only about grades
 * from their /filters selection; during their quiet hours the alert is sent silently.
 * The last seen state lives in {@code station_watch}, so restarts don't re-send alerts, and the
 * first observation of a station only records a baseline.
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
        List<FavoriteStation> all = favorites.findAll();
        if (all.isEmpty()) {
            return;
        }
        Map<Long, BotUser> active = users.findAllById(all.stream().map(FavoriteStation::getUserId).distinct().toList())
                .stream()
                .filter(u -> u.getStatus() == RegistrationStatus.APPROVED && u.isNotifyEnabled())
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
                check(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.warn("Failed to check favorite station {}: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    private void check(String osmId, List<Subscriber> subscribers) {
        StationSummaryDto summary = api.station(osmId);
        Instant updated = FuelAvailabilityService.parseTimestamp(summary.updated());
        Instant now = clock.instant();
        StationWatch watch = watches.findById(osmId).orElse(null);

        if (watch != null && (updated == null
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

        if (!was.equals(essence)) {
            log.info("Favorite station {} changed: {} -> {}", osmId, was, essence);
            CommentView latest = comments.isEmpty() ? null : comments.getFirst();
            for (Subscriber sub : subscribers) {
                notify(sub, osmId, was, essence, latest, now);
            }
        }
    }

    private void notify(Subscriber sub, String osmId, WatchLogic.Essence was, WatchLogic.Essence now,
                        CommentView latest, Instant at) {
        BotUser user = sub.user();
        FavoriteStation fav = sub.favorite();
        Settings settings = Settings.of(user);
        Set<String> filter = settings.useFilter() ? FuelFilterService.parse(user.getFuelFilter()) : Set.of();
        List<String> changes = WatchLogic.changeLines(was, now, filter);
        if (changes.isEmpty()) {
            return; // nothing this user watches has changed
        }
        InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(InlineKeyboardButton.builder().text("🗺 Маршрут").url(WatchLogic.routeUrl(fav.getLat(), fav.getLon())).build());
        row.add(InlineKeyboardButton.builder().text("💬 Отметки").callbackData(CB_COMMENTS + osmId).build());
        try {
            telegram.execute(SendMessage.builder()
                    .chatId(user.getChatId())
                    .text(WatchLogic.alertText(fav.getBrand(), fav.getAddr(), changes, now, latest))
                    .parseMode("HTML")
                    .disableNotification(settings.isQuiet(WatchLogic.hourMsk(at)))
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

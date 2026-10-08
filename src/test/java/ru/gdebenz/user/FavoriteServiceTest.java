package ru.gdebenz.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.gdebenz.fuel.FuelStatus;
import ru.gdebenz.fuel.StationView;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for favorite toggling and the cap, backed by an in-memory fake repository. */
class FavoriteServiceTest {

    private final List<FavoriteStation> store = new ArrayList<>();
    private FavoriteService service;

    @BeforeEach
    void setUp() {
        store.clear();
        FavoriteStationRepository repo = mock(FavoriteStationRepository.class);
        when(repo.findByUserIdOrderByCreatedAtAsc(anyLong())).thenAnswer(inv -> store.stream()
                .filter(f -> f.getUserId() == inv.<Long>getArgument(0)).toList());
        when(repo.findByUserIdAndOsmId(anyLong(), anyString())).thenAnswer(inv -> store.stream()
                .filter(f -> f.getUserId() == inv.<Long>getArgument(0) && f.getOsmId().equals(inv.getArgument(1)))
                .findFirst());
        when(repo.countByUserId(anyLong())).thenAnswer(inv -> store.stream()
                .filter(f -> f.getUserId() == inv.<Long>getArgument(0)).count());
        when(repo.save(any(FavoriteStation.class))).thenAnswer(inv -> {
            store.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        doAnswer(inv -> store.remove(inv.<FavoriteStation>getArgument(0))).when(repo).delete(any(FavoriteStation.class));

        service = new FavoriteService(repo, Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC));
    }

    private static StationView station(String osmId) {
        return new StationView(osmId, "Лукойл", "ул. Ленина, 12", 55.75, 37.62, 1.4,
                FuelStatus.AVAILABLE, "", "92,95", 3, null, 0.8);
    }

    @Test
    void toggleAddsThenRemoves() {
        assertThat(service.toggle(1L, station("100"))).isEqualTo(FavoriteService.Toggle.ADDED);
        assertThat(service.ids(1L)).containsExactly("100");
        assertThat(store.getFirst().getBrand()).isEqualTo("Лукойл");

        assertThat(service.toggle(1L, station("100"))).isEqualTo(FavoriteService.Toggle.REMOVED);
        assertThat(service.ids(1L)).isEmpty();
    }

    @Test
    void favoritesArePerUser() {
        service.toggle(1L, station("100"));
        service.toggle(2L, station("200"));
        assertThat(service.ids(1L)).containsExactly("100");
        assertThat(service.ids(2L)).containsExactly("200");
    }

    @Test
    void capBlocksAddingButStillAllowsRemoving() {
        for (int i = 0; i < FavoriteService.MAX_FAVORITES; i++) {
            assertThat(service.toggle(1L, station("s" + i))).isEqualTo(FavoriteService.Toggle.ADDED);
        }
        assertThat(service.toggle(1L, station("extra"))).isEqualTo(FavoriteService.Toggle.LIMIT_REACHED);
        assertThat(service.count(1L)).isEqualTo(FavoriteService.MAX_FAVORITES);

        assertThat(service.toggle(1L, station("s0"))).isEqualTo(FavoriteService.Toggle.REMOVED);
        assertThat(service.toggle(1L, station("extra"))).isEqualTo(FavoriteService.Toggle.ADDED);
    }
}

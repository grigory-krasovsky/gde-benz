package ru.gdebenz.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.gdebenz.config.RegistrationProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the registration/approval logic, backed by an in-memory fake repository
 * so no database or Spring context is needed. JPA mapping is covered by the context smoke test;
 * Flyway is verified against a real Postgres at deploy time.
 */
class RegistrationServiceTest {

    static final Duration COOLDOWN = Duration.ofMinutes(30);

    private final Map<Long, BotUser> store = new HashMap<>();
    private MutableClock clock;
    private RegistrationService service;

    @BeforeEach
    void setUp() {
        store.clear();
        clock = new MutableClock(Instant.parse("2026-01-01T12:00:00Z"));

        BotUserRepository repo = mock(BotUserRepository.class);
        when(repo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.<Long>getArgument(0))));
        when(repo.save(any(BotUser.class))).thenAnswer(inv -> {
            BotUser saved = inv.getArgument(0);
            store.put(saved.getUserId(), saved);
            return saved;
        });
        when(repo.existsByRole(any())).thenAnswer(inv ->
                store.values().stream().anyMatch(u -> u.getRole() == inv.getArgument(0)));
        when(repo.findByRole(any())).thenAnswer(inv ->
                store.values().stream().filter(u -> u.getRole() == inv.getArgument(0)).toList());
        when(repo.findByStatus(any())).thenAnswer(inv ->
                store.values().stream().filter(u -> u.getStatus() == inv.getArgument(0)).toList());

        service = new RegistrationService(repo, new RegistrationProperties(COOLDOWN), clock);
    }

    private TgUser user(long id) {
        return new TgUser(id, id, "user" + id, "User" + id);
    }

    @Test
    void firstRegistrantBecomesAdmin() {
        var result = service.register(user(1));

        assertThat(result.outcome()).isEqualTo(RegistrationService.Outcome.BECAME_ADMIN);
        BotUser admin = store.get(1L);
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getStatus()).isEqualTo(RegistrationStatus.APPROVED);
    }

    @Test
    void secondRegistrantIsPendingAndAdminIsNotified() {
        service.register(user(1)); // admin
        var result = service.register(user(2));

        assertThat(result.outcome()).isEqualTo(RegistrationService.Outcome.PENDING_CREATED);
        assertThat(result.adminChatIds()).containsExactly(1L);
        assertThat(store.get(2L).getStatus()).isEqualTo(RegistrationStatus.PENDING);
    }

    @Test
    void adminApprovesPendingUser() {
        service.register(user(1));
        service.register(user(2));

        var decision = service.decide(1L, 2L, true);

        assertThat(decision.decision()).isEqualTo(RegistrationService.Decision.DONE_APPROVED);
        assertThat(store.get(2L).getStatus()).isEqualTo(RegistrationStatus.APPROVED);
    }

    @Test
    void nonAdminCannotDecide() {
        service.register(user(1));
        service.register(user(2));

        var decision = service.decide(2L, 2L, true); // user 2 is not an admin

        assertThat(decision.decision()).isEqualTo(RegistrationService.Decision.NOT_AUTHORIZED);
    }

    @Test
    void rejectedUserBlockedDuringCooldownThenCanReRegister() {
        service.register(user(1));
        service.register(user(2));
        service.decide(1L, 2L, false); // reject

        var blocked = service.register(user(2));
        assertThat(blocked.outcome()).isEqualTo(RegistrationService.Outcome.REJECTED_COOLDOWN);
        assertThat(blocked.cooldownRemaining()).isNotNull();

        clock.advance(COOLDOWN.plusMinutes(1));

        var again = service.register(user(2));
        assertThat(again.outcome()).isEqualTo(RegistrationService.Outcome.REREQUESTED);
        assertThat(store.get(2L).getStatus()).isEqualTo(RegistrationStatus.PENDING);
    }

    @Test
    void alreadyApprovedIsReported() {
        service.register(user(1));
        var result = service.register(user(1));

        assertThat(result.outcome()).isEqualTo(RegistrationService.Outcome.ALREADY_APPROVED);
    }

    /** A test clock whose instant can be advanced to exercise the cooldown. */
    static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration delta) {
            this.instant = this.instant.plus(delta);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public long millis() {
            return instant.toEpochMilli();
        }
    }
}

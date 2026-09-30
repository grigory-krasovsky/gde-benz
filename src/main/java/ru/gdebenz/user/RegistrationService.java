package ru.gdebenz.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gdebenz.config.RegistrationProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Access control: users request access with {@code /register}; the first ever registrant becomes
 * the admin, subsequent registrants wait for the admin's approval. All decisions are persisted.
 */
@Service
public class RegistrationService {

    public enum Outcome {
        BECAME_ADMIN,
        PENDING_CREATED,
        REREQUESTED,
        ALREADY_APPROVED,
        ALREADY_PENDING,
        REJECTED_COOLDOWN
    }

    public record RegisterResult(Outcome outcome, BotUser user,
                                 Duration cooldownRemaining, List<Long> adminChatIds) {

        static RegisterResult of(Outcome outcome, BotUser user) {
            return new RegisterResult(outcome, user, null, List.of());
        }

        static RegisterResult pending(Outcome outcome, BotUser user, List<Long> adminChatIds) {
            return new RegisterResult(outcome, user, null, adminChatIds);
        }

        static RegisterResult cooldown(BotUser user, Duration remaining) {
            return new RegisterResult(Outcome.REJECTED_COOLDOWN, user, remaining, List.of());
        }
    }

    public enum Decision {
        DONE_APPROVED,
        DONE_REJECTED,
        NOT_AUTHORIZED,
        TARGET_NOT_FOUND,
        ALREADY_DECIDED
    }

    public record DecisionResult(Decision decision, BotUser target) {
    }

    private final BotUserRepository repo;
    private final RegistrationProperties props;
    private final Clock clock;

    public RegistrationService(BotUserRepository repo, RegistrationProperties props, Clock clock) {
        this.repo = repo;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public RegisterResult register(TgUser tg) {
        Instant now = clock.instant();
        Optional<BotUser> existingOpt = repo.findById(tg.id());

        if (existingOpt.isPresent()) {
            BotUser user = existingOpt.get();
            user.refreshProfile(tg.chatId(), tg.username(), tg.firstName(), now);
            return switch (user.getStatus()) {
                case APPROVED -> RegisterResult.of(Outcome.ALREADY_APPROVED, user);
                case PENDING -> RegisterResult.of(Outcome.ALREADY_PENDING, user);
                case REJECTED -> reconsiderRejected(user, now);
            };
        }

        // Brand-new user. The first registrant ever becomes the admin.
        if (!repo.existsByRole(Role.ADMIN)) {
            BotUser admin = new BotUser(tg.id(), tg.chatId(), tg.username(), tg.firstName(),
                    Role.ADMIN, RegistrationStatus.APPROVED, now);
            repo.save(admin);
            return RegisterResult.of(Outcome.BECAME_ADMIN, admin);
        }

        BotUser pending = new BotUser(tg.id(), tg.chatId(), tg.username(), tg.firstName(),
                Role.USER, RegistrationStatus.PENDING, now);
        repo.save(pending);
        return RegisterResult.pending(Outcome.PENDING_CREATED, pending, adminChatIds());
    }

    private RegisterResult reconsiderRejected(BotUser user, Instant now) {
        Duration elapsed = Duration.between(user.getStatusChangedAt(), now);
        Duration cooldown = props.rejectCooldown();
        if (elapsed.compareTo(cooldown) < 0) {
            return RegisterResult.cooldown(user, cooldown.minus(elapsed));
        }
        user.changeStatus(RegistrationStatus.PENDING, now);
        return RegisterResult.pending(Outcome.REREQUESTED, user, adminChatIds());
    }

    @Transactional
    public DecisionResult decide(long adminUserId, long targetUserId, boolean approve) {
        BotUser admin = repo.findById(adminUserId).orElse(null);
        if (admin == null || admin.getRole() != Role.ADMIN || admin.getStatus() != RegistrationStatus.APPROVED) {
            return new DecisionResult(Decision.NOT_AUTHORIZED, null);
        }
        BotUser target = repo.findById(targetUserId).orElse(null);
        if (target == null) {
            return new DecisionResult(Decision.TARGET_NOT_FOUND, null);
        }
        if (target.getStatus() != RegistrationStatus.PENDING) {
            return new DecisionResult(Decision.ALREADY_DECIDED, target);
        }
        Instant now = clock.instant();
        target.changeStatus(approve ? RegistrationStatus.APPROVED : RegistrationStatus.REJECTED, now);
        return new DecisionResult(approve ? Decision.DONE_APPROVED : Decision.DONE_REJECTED, target);
    }

    @Transactional(readOnly = true)
    public List<BotUser> pending() {
        return repo.findByStatus(RegistrationStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public boolean isAdmin(long userId) {
        return repo.findById(userId)
                .map(u -> u.getRole() == Role.ADMIN && u.getStatus() == RegistrationStatus.APPROVED)
                .orElse(false);
    }

    private List<Long> adminChatIds() {
        return repo.findByRole(Role.ADMIN).stream().map(BotUser::getChatId).toList();
    }
}

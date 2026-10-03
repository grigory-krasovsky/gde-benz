package ru.gdebenz.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A Telegram user known to the bot, with a registration status and role.
 * The primary key is the Telegram user id.
 */
@Entity
@Table(name = "bot_users")
public class BotUser {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "username")
    private String username;

    @Column(name = "first_name")
    private String firstName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RegistrationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    @Column(name = "fuel_filter", length = 64)
    private String fuelFilter;

    protected BotUser() {
        // for JPA
    }

    public BotUser(Long userId, Long chatId, String username, String firstName,
                   Role role, RegistrationStatus status, Instant now) {
        this.userId = userId;
        this.chatId = chatId;
        this.username = username;
        this.firstName = firstName;
        this.role = role;
        this.status = status;
        this.createdAt = now;
        this.updatedAt = now;
        this.statusChangedAt = now;
    }

    /** Refresh mutable profile fields without touching the status timestamp. */
    public void refreshProfile(Long chatId, String username, String firstName, Instant now) {
        this.chatId = chatId;
        this.username = username;
        this.firstName = firstName;
        this.updatedAt = now;
    }

    public void changeStatus(RegistrationStatus newStatus, Instant now) {
        this.status = newStatus;
        this.statusChangedAt = now;
        this.updatedAt = now;
    }

    public void setFuelFilter(String fuelFilter, Instant now) {
        this.fuelFilter = fuelFilter;
        this.updatedAt = now;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getChatId() {
        return chatId;
    }

    public String getUsername() {
        return username;
    }

    public String getFirstName() {
        return firstName;
    }

    public Role getRole() {
        return role;
    }

    public RegistrationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getStatusChangedAt() {
        return statusChangedAt;
    }

    public String getFuelFilter() {
        return fuelFilter;
    }
}

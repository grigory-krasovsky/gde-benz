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

    @Column(name = "notify_enabled", nullable = false)
    private boolean notifyEnabled = true;

    @Column(name = "notify_use_filter", nullable = false)
    private boolean notifyUseFilter;

    @Column(name = "notify_chat", nullable = false)
    private boolean notifyChat = true;

    @Column(name = "quiet_enabled", nullable = false)
    private boolean quietEnabled = true;

    @Column(name = "quiet_from", nullable = false)
    private int quietFrom = 23;

    @Column(name = "quiet_to", nullable = false)
    private int quietTo = 7;

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

    public void setNotifications(boolean notifyEnabled, boolean notifyUseFilter, boolean notifyChat,
                                 boolean quietEnabled, int quietFrom, int quietTo, Instant now) {
        this.notifyEnabled = notifyEnabled;
        this.notifyUseFilter = notifyUseFilter;
        this.notifyChat = notifyChat;
        this.quietEnabled = quietEnabled;
        this.quietFrom = quietFrom;
        this.quietTo = quietTo;
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

    public boolean isNotifyEnabled() {
        return notifyEnabled;
    }

    public boolean isNotifyUseFilter() {
        return notifyUseFilter;
    }

    public boolean isNotifyChat() {
        return notifyChat;
    }

    public boolean isQuietEnabled() {
        return quietEnabled;
    }

    public int getQuietFrom() {
        return quietFrom;
    }

    public int getQuietTo() {
        return quietTo;
    }
}

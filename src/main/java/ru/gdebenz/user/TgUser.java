package ru.gdebenz.user;

/** Minimal Telegram user info extracted from an incoming update. */
public record TgUser(long id, long chatId, String username, String firstName) {
}

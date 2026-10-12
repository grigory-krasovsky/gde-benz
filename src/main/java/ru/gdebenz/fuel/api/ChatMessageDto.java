package ru.gdebenz.fuel.api;

/**
 * One free-text message from a station chat (api.gdebenz.ru/api/chats/{osm_id}); newest first.
 * {@code createdAt} is ISO-8601 UTC (e.g. "2026-10-11T18:19:10.184919Z").
 */
public record ChatMessageDto(
        long id,
        String authorName,
        String body,
        String createdAt) {
}

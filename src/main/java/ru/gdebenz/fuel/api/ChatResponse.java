package ru.gdebenz.fuel.api;

import java.util.List;

/** Response of api.gdebenz.ru/api/chats/{osm_id}?limit=N: {@code {"comments": [...]}}. */
public record ChatResponse(List<ChatMessageDto> comments) {
}

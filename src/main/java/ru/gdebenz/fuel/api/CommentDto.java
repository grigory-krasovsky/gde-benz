package ru.gdebenz.fuel.api;

/**
 * One driver report for a station, from /api/comments/{osm_id}/recent.
 * Field names map from snake_case via the client's ObjectMapper; unknown fields are ignored.
 */
public record CommentDto(
        String status,
        String detail,
        String createdAt,
        boolean edited,
        boolean onSite,
        boolean authorReliable,
        int authorTier,
        boolean acctOk,
        boolean svc) {
}

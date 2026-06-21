package kz.savefood.chat.dto;

/** Port of chat_routes.py {@code MessageIn} (body min 1 / max 2000, checked in the controller). */
public record MessageIn(String body) {
}

package ru.savefood.push.dto;
/** Port of push_routes.py {@code FcmRegisterIn}. */
public record FcmRegisterIn(String token, String role, Integer relatedId) {
}

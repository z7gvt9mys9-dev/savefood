package kz.savefood.auth;

/** Body of {@code POST /auth/telegram/login/poll} — oauth_routes.py {@code TelegramPoll}. */
public record TelegramPoll(String token) {
}

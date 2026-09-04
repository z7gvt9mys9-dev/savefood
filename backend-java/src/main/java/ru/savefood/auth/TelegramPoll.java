package ru.savefood.auth;
/** Token body shared by the Telegram login status, completion, and cancellation endpoints. */
public record TelegramPoll(String token) {
}

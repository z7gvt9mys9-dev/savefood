package ru.savefood.partner.dto;

import java.util.List;

/** Port of partner_api.py {@code WebhookIn}. {@code events} defaults to ["*"] when omitted. */
public record WebhookIn(String url, List<String> events) {
}

package ru.savefood.volunteer.dto;

/**
 * Port of schemas.py {@code StartRouteRequest}. {@code maxStops} null ⇒ serve all
 * reserved in-window recipients up to ROUTE_HARD_CAP (§59/Q2); an explicit value
 * must be ≥ 1 (the pydantic ge=1 — enforced in the controller as a 422).
 */
public record StartRouteRequest(Integer lotId, Integer maxStops) {
}

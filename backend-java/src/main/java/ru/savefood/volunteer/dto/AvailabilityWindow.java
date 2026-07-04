package ru.savefood.volunteer.dto;

/**
 * One weekly availability window (schemas.py {@code AvailabilityWindow}, §54):
 * {@code day} 0=Mon…6=Sun, {@code start}/{@code end} as "HH:MM" (LOCAL_TZ). Range
 * and pattern are validated in the controller, mirroring the pydantic Field rules.
 */
public record AvailabilityWindow(Integer day, String start, String end) {
}

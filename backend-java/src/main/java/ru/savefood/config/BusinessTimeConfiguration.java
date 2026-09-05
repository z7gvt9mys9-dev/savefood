package ru.savefood.config;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BusinessTimeConfiguration {
    @Bean
    public Clock businessClock(@Value("${savefood.local-tz}") String localTz) {
        return systemClock(localTz);
    }

    public static Clock systemClock(String localTz) {
        try {
            return Clock.system(ZoneId.of(localTz));
        } catch (DateTimeException | NullPointerException e) {
            throw new IllegalStateException("Invalid savefood.local-tz: " + localTz, e);
        }
    }
}

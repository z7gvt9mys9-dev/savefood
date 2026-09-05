package ru.savefood.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BodyLimitConfigurationTest {
    private final String nginx = read(Path.of("..", "savefood", "nginx.conf"));

    @Test
    void nginxUsesSmallDefaultsAndBoundedTelegramAdmission() {
        assertThat(nginx).contains("client_max_body_size 1m;")
            .doesNotContain("client_max_body_size 60m;")
            .contains("limit_req_zone $client_real_ip zone=telegram_webhook:1m rate=30r/s;");
        assertLocation("location = /telegram/webhook", "client_max_body_size 64k;");
        assertLocation("location = /telegram/webhook",
            "limit_req zone=telegram_webhook burst=100 nodelay;");
    }

    @Test
    void onlyExactMultipartRoutesOverrideTheDefault() {
        assertLocation("location ~ ^/shops/[0-9]+/lots/upload$", "client_max_body_size 30m;");
        for (String location : new String[] {
                "location ~ ^/shops/[0-9]+/lot-photos$",
                "location ~ ^/shops/[0-9]+/receipts$",
                "location ~ ^/volunteers/[0-9]+/document/upload$",
                "location ~ ^/volunteers/route/[0-9]+/ticket/[0-9]+/photo$",
                "location ~ ^/needy/[0-9]+/ticket/[0-9]+/photo$"}) {
            assertLocation(location, "client_max_body_size 6m;");
        }
        assertThat(nginx).doesNotContain("location /api")
            .doesNotContain("location ^~ /api")
            .doesNotContain("location ~ ^/api.*client_max_body_size");
    }

    @Test
    void tomcatDoesNotSwallowRejectedBodiesWithoutBound() {
        String application = read(Path.of("src", "main", "resources", "application.yml"));
        assertThat(application).contains("max-swallow-size: 1MB")
            .contains("max-file-size: 15MB")
            .contains("max-request-size: 60MB");
    }

    private void assertLocation(String marker, String expected) {
        int start = nginx.indexOf(marker);
        assertThat(start).as(marker).isGreaterThanOrEqualTo(0);
        int end = nginx.indexOf("\n    }", start);
        assertThat(end).as("closing brace for " + marker).isGreaterThan(start);
        assertThat(nginx.substring(start, end)).contains(expected);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

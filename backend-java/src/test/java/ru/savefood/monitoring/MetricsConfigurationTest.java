package ru.savefood.monitoring;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsConfigurationTest {

    @Test
    void composeRequiresNonEmptyMetricsTokenAndNginxRelaysBearerAuthorization() throws Exception {
        String compose = Files.readString(Path.of("..", "docker-compose.yml"));
        String nginx = Files.readString(Path.of("..", "savefood", "nginx.conf"));
        String metricsLocation = nginx.substring(nginx.indexOf("location = /metrics"),
            nginx.indexOf("    }", nginx.indexOf("location = /metrics")));

        assertThat(compose).contains("METRICS_TOKEN: ${METRICS_TOKEN:?METRICS_TOKEN must be set and non-empty}");
        assertThat(metricsLocation).contains("proxy_set_header Authorization $http_authorization;");
        assertThat(metricsLocation).doesNotContain("allow ").doesNotContain("deny all");
    }
}

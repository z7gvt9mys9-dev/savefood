package ru.savefood.monitoring;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.savefood.cache.CacheService;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
class MetricsAuthorizationTest {
    private static final String TOKEN = "metrics-test-secret";
    @Test
    void metricsRequiresBearerTokenRegardlessOfImmediateSource() throws Exception {
        MockMvc mvc = mvcWith(TOKEN);
        mvc.perform(get("/metrics"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/metrics").queryParam("token", TOKEN))
            .andExpect(status().isForbidden());
        mvc.perform(get("/metrics").with(remoteAddress("127.0.0.1")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/metrics").with(remoteAddress("172.20.0.5")))
            .andExpect(status().isForbidden());
    }
    @Test
    void emptyConfiguredTokenFailsClosed() throws Exception {
        MockMvc mvc = mvcWith("   ");
        mvc.perform(get("/metrics").header("Authorization", "Bearer any-value"))
            .andExpect(status().isForbidden());
    }
    @Test
    void wrongTokenIsRejected() throws Exception {
        mvcWith(TOKEN).perform(get("/metrics").header("Authorization", "Bearer wrong-token"))
            .andExpect(status().isForbidden());
    }
    @Test
    void correctTokenIsAllowedThroughForwardedPublicProxy() throws Exception {
        mvcWith(TOKEN).perform(get("/metrics")
                .with(remoteAddress("127.0.0.1"))
                .header("X-Forwarded-For", "198.51.100.25")
                .header("Authorization", "Bearer " + TOKEN))
            .andExpect(status().isOk());
    }
    @Test
    void forwardedPublicProxyWithoutTokenIsRejected() throws Exception {
        mvcWith(TOKEN).perform(get("/metrics")
                .with(remoteAddress("127.0.0.1"))
                .header("X-Forwarded-For", "198.51.100.25"))
            .andExpect(status().isForbidden());
    }
    private static MockMvc mvcWith(String token) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        return standaloneSetup(new MonitoringController(new MetricsService(jdbc, token), jdbc,
                mock(CacheService.class)))
            .build();
    }
    private static RequestPostProcessor remoteAddress(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}

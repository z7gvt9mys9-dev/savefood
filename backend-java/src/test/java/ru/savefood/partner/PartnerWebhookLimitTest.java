package ru.savefood.partner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import ru.savefood.billing.BillingService;
import ru.savefood.esg.EsgService;
import ru.savefood.match.NeedsMatchService;
import ru.savefood.partner.dto.WebhookIn;
import ru.savefood.security.CurrentUser;
import ru.savefood.shop.ShopRepository;
import ru.savefood.shop.ShopService;
import ru.savefood.web.ApiException;
import ru.savefood.webhook.WebhookProperties;

class PartnerWebhookLimitTest {
    @Test
    void registrationRejectsWhenThePerShopLimitHasBeenReached() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Integer>>any(),
            any(Object[].class))).thenReturn(List.of());
        ShopRepository shops = mock(ShopRepository.class);
        when(shops.getShopById(7)).thenReturn(java.util.Map.of("id", 7));
        WebhookProperties limits = new WebhookProperties();
        limits.setMaxPerShop(2);
        PartnerApiController controller = new PartnerApiController(jdbc, mock(BillingService.class),
            mock(EsgService.class), mock(NeedsMatchService.class), mock(ShopService.class), shops, limits);

        ApiException error = assertThrows(ApiException.class, () -> controller.createWebhook(7,
            new WebhookIn("https://partner.example/hook", List.of("*")),
            new CurrentUser(1, "admin", "admin", null)));

        assertEquals(409, error.getStatus());
        verify(jdbc).query(contains("COUNT(*)"), org.mockito.ArgumentMatchers.<RowMapper<Integer>>any(),
            eq(7), eq(7), anyString(), anyString(), eq("*"), eq(7), eq(2));
    }
}

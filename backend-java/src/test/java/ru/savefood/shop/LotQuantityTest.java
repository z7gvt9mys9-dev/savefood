package ru.savefood.shop;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.savefood.billing.BillingService;
import ru.savefood.esg.EsgService;
import ru.savefood.forecast.ForecastService;
import ru.savefood.match.NeedsMatchService;
import ru.savefood.needy.NeedyService;
import ru.savefood.receipt.ReceiptService;
import ru.savefood.security.CurrentUser;
import ru.savefood.security.PasswordService;
import ru.savefood.shop.dto.ReceiptLotDraft;
import ru.savefood.shop.dto.LotUpdate;
import ru.savefood.upload.UploadService;
import ru.savefood.web.ApiException;
import ru.savefood.web.RateLimiter;
import ru.savefood.webhook.WebhookService;

class LotQuantityTest {

    @Test
    void acceptsWholeReservableUnitCounts() {
        assertThatCode(() -> LotQuantity.requireWholeUnits(1.0, "quantity")).doesNotThrowAnyException();
        assertThatCode(() -> LotQuantity.requireWholeUnits(10.0, "quantity")).doesNotThrowAnyException();
    }

    @Test
    void rejectsZeroAndFractionalUnitCounts() {
        assertThatThrownBy(() -> LotQuantity.requireWholeUnits(0.0, "quantity"))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> LotQuantity.requireWholeUnits(0.5, "quantity"))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> LotQuantity.requireWholeUnits(2.5, "quantity"))
            .isInstanceOf(ApiException.class);
    }

    @Test
    void patchRejectsAFractionalQuantityBeforeItCanReachTheRepository() {
        ShopRepository repo = mock(ShopRepository.class);
        when(repo.getLotById(7)).thenReturn(Map.of(
            "shop_id", 1, "quantity", 3.0, "initial_quantity", 3.0,
            "unit", "кг", "unit_weight_kg", 1.0));
        ShopController controller = new ShopController(repo, mock(ShopService.class),
            mock(BillingService.class), mock(ReceiptService.class), mock(ForecastService.class),
            mock(EsgService.class), mock(WebhookService.class), mock(NeedsMatchService.class),
            mock(UploadService.class), mock(RateLimiter.class), mock(LotPhotoReferenceService.class),
            "/tmp", "/tmp");

        assertThatThrownBy(() -> controller.patchLot(7,
            new LotUpdate(null, 2.5, null, null, null, null, null, null, null),
            new CurrentUser(1, "shop", "shop", 1)))
            .isInstanceOf(ApiException.class);

        verify(repo, never()).updateLot(org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    }

    @Test
    void receiptConfirmationRejectsAFractionalDraftBeforeCreatingInventory() {
        ShopRepository repo = mock(ShopRepository.class);
        BillingService billing = mock(BillingService.class);
        when(repo.getReceiptForUpdate(3)).thenReturn(Map.of("shop_id", 1, "status", "parsed"));
        ShopService service = service(repo, billing);

        assertThatThrownBy(() -> service.confirmReceiptLots(1, 3,
            List.of(new ReceiptLotDraft("food", 2.5, "Bakery")), LocalDate.now(), null, null))
            .isInstanceOf(ApiException.class);

        verifyNoInteractions(billing);
    }

    private static ShopService service(ShopRepository repo, BillingService billing) {
        return new ShopService(mock(org.springframework.jdbc.core.JdbcTemplate.class), repo, billing,
            mock(NeedyService.class), mock(PasswordService.class), mock(UploadService.class),
            mock(LotUploadCleanup.class));
    }
}

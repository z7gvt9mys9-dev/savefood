package ru.savefood.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.savefood.billing.BillingService;
import ru.savefood.esg.EsgService;
import ru.savefood.forecast.ForecastService;
import ru.savefood.match.NeedsMatchService;
import ru.savefood.needy.NeedyService;
import ru.savefood.receipt.ReceiptService;
import ru.savefood.security.CurrentUser;
import ru.savefood.security.PasswordService;
import ru.savefood.shop.dto.LotCreate;
import ru.savefood.upload.UploadService;
import ru.savefood.web.ApiException;
import ru.savefood.web.RateLimiter;
import ru.savefood.webhook.WebhookService;

class PrivateDonorLotPhotoReferenceTest {

    @Test
    void validServerReferenceCreatesPrivateLotAndConsumesReference() {
        Fixture fixture = fixture("private");
        String filename = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png";
        when(fixture.references.requireAvailable(1, "/uploads/" + filename)).thenReturn(filename);
        when(fixture.service.createLotWithClaimedPhoto(anyInt(), anyString(), anyDouble(), any(), anyString(),
            any(), any(), any(), any(), anyBoolean(), anyString(), anyDouble())).thenReturn(17);

        assertThat(fixture.controller.createLot(1, payload("/uploads/" + filename), shopUser()))
            .containsEntry("id", 17);
        verify(fixture.service).createLotWithClaimedPhoto(eq(1), anyString(), anyDouble(), any(), eq(filename),
            any(), any(), any(), any(), anyBoolean(), anyString(), anyDouble());
    }

    @Test
    void rejectedReferenceCannotCreatePrivateLot() {
        Fixture fixture = fixture("private");
        when(fixture.references.requireAvailable(1, "x"))
            .thenThrow(new ApiException(400, "invalid reference"));

        assertThatThrownBy(() -> fixture.controller.createLot(1, payload("x"), shopUser()))
            .isInstanceOf(ApiException.class);
        verify(fixture.service, never()).createLotWithClaimedPhoto(anyInt(), anyString(), anyDouble(), any(),
            anyString(), any(), any(), any(), any(), anyBoolean(), anyString(), anyDouble());
    }

    @Test
    void nonPrivateJsonLotBehaviorIsUnchanged() {
        Fixture fixture = fixture("business");
        when(fixture.service.createLot(anyInt(), anyString(), anyDouble(), any(), any(), any(), any(), any(),
            any(), anyBoolean(), anyString(), anyDouble())).thenReturn(9);

        assertThat(fixture.controller.createLot(1, payload("https://legacy.example/photo.png"), shopUser()))
            .containsEntry("id", 9);
        verify(fixture.references, never()).requireAvailable(anyInt(), anyString());
        verify(fixture.service).createLot(eq(1), anyString(), anyDouble(), any(),
            eq("https://legacy.example/photo.png"), any(), any(), any(), any(), anyBoolean(), anyString(), anyDouble());
    }

    @Test
    void failedClaimRollsBackTheNewLotInsteadOfReusingThePhoto() {
        ShopRepository repo = mock(ShopRepository.class);
        when(repo.createLot(anyInt(), anyString(), anyDouble(), any(), anyString(), any(), any(), any(), any(),
            anyBoolean(), anyString(), anyDouble())).thenReturn(41);
        when(repo.claimLotPhotoUpload(1, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png", 41)).thenReturn(false);
        ShopService service = new ShopService(mock(JdbcTemplate.class), repo, mock(BillingService.class),
            mock(NeedyService.class), mock(PasswordService.class), mock(UploadService.class),
            mock(LotUploadCleanup.class));

        assertThatThrownBy(() -> service.createLotWithClaimedPhoto(1, "food", 1, null,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png", null, null, null, null, false, "кг", 1))
            .isInstanceOf(ApiException.class);
        verify(repo).claimLotPhotoUpload(1, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png", 41);
    }

    @Test
    void successfulClaimPersistsTheServerManagedPhotoOnTheLot() {
        ShopRepository repo = mock(ShopRepository.class);
        String filename = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png";
        when(repo.createLot(anyInt(), anyString(), anyDouble(), any(), anyString(), any(), any(), any(), any(),
            anyBoolean(), anyString(), anyDouble())).thenReturn(42);
        when(repo.claimLotPhotoUpload(1, filename, 42)).thenReturn(true);
        ShopService service = new ShopService(mock(JdbcTemplate.class), repo, mock(BillingService.class),
            mock(NeedyService.class), mock(PasswordService.class), mock(UploadService.class),
            mock(LotUploadCleanup.class));

        assertThat(service.createLotWithClaimedPhoto(1, "food", 1, null, filename,
            null, null, null, null, false, "кг", 1)).isEqualTo(42);
        verify(repo).createLot(eq(1), anyString(), anyDouble(), any(), eq("/uploads/" + filename),
            any(), any(), any(), any(), anyBoolean(), anyString(), anyDouble());
        verify(repo).claimLotPhotoUpload(1, filename, 42);
    }

    private static LotCreate payload(String photo) {
        return new LotCreate("food", 1.0, "кг", 1.0, null, photo, null, null, null, null, false);
    }

    private static CurrentUser shopUser() {
        return new CurrentUser(1, "shop", "shop", 1);
    }

    private static Fixture fixture(String kind) {
        ShopRepository repo = mock(ShopRepository.class);
        ShopService service = mock(ShopService.class);
        LotPhotoReferenceService references = mock(LotPhotoReferenceService.class);
        when(repo.getShopById(1)).thenReturn(Map.of("kind", kind));
        ShopController controller = new ShopController(repo, service, mock(BillingService.class),
            mock(ReceiptService.class), mock(ForecastService.class), mock(EsgService.class),
            mock(WebhookService.class), mock(NeedsMatchService.class), mock(UploadService.class),
            mock(RateLimiter.class), references, "/tmp", "/tmp");
        return new Fixture(controller, service, references);
    }

    private record Fixture(ShopController controller, ShopService service, LotPhotoReferenceService references) {
    }
}

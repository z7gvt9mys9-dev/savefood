package ru.savefood.needy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import ru.savefood.admin.AdminController;
import ru.savefood.cache.CacheService;
import ru.savefood.needy.dto.TicketCreate;
import ru.savefood.photo.PhotoModerationService;
import ru.savefood.security.CurrentUser;
import ru.savefood.shop.ShopRepository;
import ru.savefood.telegram.TelegramService;
import ru.savefood.upload.UploadService;
import ru.savefood.web.RateLimiter;

class NeedyKycRemovalTest {

    @Test
    void legacyKycStatesNoLongerBlockRecipientFunctionality() {
        assertThat(Stream.of("active", "pending", "approved", "rejected")
            .allMatch(NeedyController::isUsableRecipientStatus)).isTrue();
        assertThat(NeedyController.isUsableRecipientStatus("deleted")).isFalse();
        assertThat(NeedyController.isUsableRecipientStatus("blocked")).isFalse();
    }

    @Test
    void pendingAndRejectedLegacyRecipientsCanCreateTickets() {
        for (String legacyStatus : Set.of("pending", "rejected")) {
            NeedyRepository repo = mock(NeedyRepository.class);
            NeedyService service = mock(NeedyService.class);
            when(repo.getNeedyById(42)).thenReturn(java.util.Map.of("status", legacyStatus));
            when(service.createTicket(42, null, null, null, null, null, 7,
                null, null, null, true)).thenReturn(9);
            when(repo.getTicketById(9)).thenReturn(java.util.Map.of("qr_secret", "secret"));
            NeedyController controller = new NeedyController(repo, service,
                mock(ShopRepository.class), mock(CacheService.class), mock(UploadService.class),
                mock(PhotoModerationService.class), mock(RateLimiter.class), mock(TelegramService.class),
                "/tmp/delivery", "/tmp/legacy");

            assertThat(controller.createTicket(42,
                new TicketCreate(null, null, null, null, null, 7, null, null, null, true),
                new CurrentUser(1, "recipient", "needy", 42)))
                .containsEntry("id", 9);
        }
    }

    @Test
    void recipientUploadDocumentAndModerationEndpointsAreGone() {
        Set<String> paths = pathsOn(NeedyController.class);

        assertThat(paths).doesNotContain(
            "/needy/{needyId}/profile/upload",
            "/needy/{needyId}/document",
            "/needy/{needyId}/moderation",
            "/needy/{needyId}/kyc_recheck");
    }

    @Test
    void adminHasNoRecipientKycQueueOrDecisionEndpoint() {
        Set<String> paths = pathsOn(AdminController.class);

        assertThat(paths).doesNotContain("/needy", "/needy/{needyId}/moderation");
    }

    private static Set<String> pathsOn(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
            .flatMap(NeedyKycRemovalTest::mappingPaths)
            .collect(Collectors.toSet());
    }

    private static Stream<String> mappingPaths(Method method) {
        GetMapping get = method.getAnnotation(GetMapping.class);
        PostMapping post = method.getAnnotation(PostMapping.class);
        PatchMapping patch = method.getAnnotation(PatchMapping.class);
        if (get != null) return Arrays.stream(get.value());
        if (post != null) return Arrays.stream(post.value());
        if (patch != null) return Arrays.stream(patch.value());
        return Stream.empty();
    }
}

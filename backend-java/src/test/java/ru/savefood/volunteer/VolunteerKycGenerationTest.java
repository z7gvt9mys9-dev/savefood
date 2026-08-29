package ru.savefood.volunteer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.multipart.MultipartFile;
import ru.savefood.audit.AuditService;
import ru.savefood.kyc.KycCrypto;
import ru.savefood.kyc.KycService;
import ru.savefood.photo.PhotoModerationService;
import ru.savefood.photo.DeliveryPhotoStorage;
import ru.savefood.security.CurrentUser;
import ru.savefood.storage.SensitiveFileCleanup;
import ru.savefood.storage.SensitiveFileCleanup.Storage;
import ru.savefood.telegram.TelegramService;
import ru.savefood.upload.UploadService;
import ru.savefood.web.RateLimiter;
import ru.savefood.webhook.WebhookService;

class VolunteerKycGenerationTest {

    @TempDir
    Path uploadDir;

    @Test
    void uploadCreatesOneGenerationAndPassesItToAnalysis() {
        Fixture fixture = fixture();
        when(fixture.repo.getVolunteerById(7)).thenReturn(volunteerRow("/volunteer_kyc/a.pdf", "generation-a"));
        when(fixture.uploads.validateAndSave(fixture.file, uploadDir.toString(), true)).thenReturn("b.pdf");
        when(fixture.repo.replaceVolunteerKycDocument(eq(7), eq("/volunteer_kyc/b.pdf"), anyString()))
            .thenReturn(new VolunteerRepository.KycDocumentReplacement("/volunteer_kyc/a.pdf"));

        fixture.controller.uploadDocument(7, fixture.file,
            new CurrentUser(1, "volunteer", "volunteer", 7), fixture.request);

        ArgumentCaptor<String> generation = ArgumentCaptor.forClass(String.class);
        verify(fixture.repo).replaceVolunteerKycDocument(
            eq(7), eq("/volunteer_kyc/b.pdf"), generation.capture());
        UUID.fromString(generation.getValue());
        verify(fixture.sensitiveFiles).deleteOnRollback(
            Storage.VOLUNTEER_KYC, "/volunteer_kyc/b.pdf");
        verify(fixture.kyc).startVolunteerKycCheck(7,
            uploadDir.resolve("b.pdf").toString(), "Volunteer", generation.getValue());
        verify(fixture.sensitiveFiles).trackAndDeleteAfterCommit(
            Storage.VOLUNTEER_KYC, "/volunteer_kyc/a.pdf");
    }

    @Test
    void recheckPreservesTheCurrentDocumentGeneration() {
        Fixture fixture = fixture();
        Map<String, Object> row = volunteerRow("/volunteer_kyc/a.pdf", "generation-a");
        when(fixture.repo.getVolunteerById(7)).thenReturn(row);

        Map<String, Object> response = fixture.controller.recheck(
            7, new CurrentUser(1, "admin", "admin", null));

        verify(fixture.kyc).recheckVolunteer(
            7, uploadDir.resolve("a.pdf").toString(), "Volunteer", "generation-a");
        assertThat(response).containsEntry("kyc_verdict", "unchecked");
    }

    private Fixture fixture() {
        VolunteerRepository repo = mock(VolunteerRepository.class);
        UploadService uploads = mock(UploadService.class);
        KycService kyc = mock(KycService.class);
        MultipartFile file = mock(MultipartFile.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        SensitiveFileCleanup sensitiveFiles = mock(SensitiveFileCleanup.class);
        VolunteerController controller = new VolunteerController(
            repo, mock(VolunteerService.class), mock(RateLimiter.class), uploads,
            mock(KycCrypto.class), kyc, mock(PhotoModerationService.class),
            mock(WebhookService.class), mock(TelegramService.class), mock(JdbcTemplate.class),
            mock(AuditService.class), sensitiveFiles, mock(DeliveryPhotoStorage.class),
            true, uploadDir.toString(), uploadDir.toString());
        return new Fixture(controller, repo, uploads, kyc, sensitiveFiles, file, request);
    }

    private static Map<String, Object> volunteerRow(String document, String generation) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 7);
        row.put("name", "Volunteer");
        row.put("status", "pending");
        row.put("document", document);
        row.put("kyc_generation", generation);
        return row;
    }

    private record Fixture(VolunteerController controller, VolunteerRepository repo,
                           UploadService uploads, KycService kyc, SensitiveFileCleanup sensitiveFiles,
                           MultipartFile file,
                           HttpServletRequest request) {
    }
}

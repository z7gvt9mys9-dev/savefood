package ru.savefood.kyc;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.savefood.audit.AuditService;
import ru.savefood.telegram.TelegramService;
import ru.savefood.volunteer.VolunteerRepository;

class KycServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void volunteerKycStillRecordsUncheckedWhenAiIsUnavailable() throws Exception {
        Path document = tempDir.resolve("volunteer.jpg");
        java.nio.file.Files.write(document, new byte[] {1, 2, 3});
        VolunteerRepository volunteers = mock(VolunteerRepository.class);
        KycCrypto crypto = mock(KycCrypto.class);
        when(crypto.readDecrypted(document.toString())).thenReturn(new byte[] {1, 2, 3});
        KycService service = new KycService(mock(JdbcTemplate.class), volunteers,
            mock(AuditService.class), crypto, mock(TelegramService.class), "", "model", 0.7, 0.3);

        service.runVolunteerKycCheck(17, document.toString(), "Volunteer");

        verify(volunteers).saveVolunteerKyc(eq(17), isNull(), eq("unchecked"), contains("повторена"));
    }
}


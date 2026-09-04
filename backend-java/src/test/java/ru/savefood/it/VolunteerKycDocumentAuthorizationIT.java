package ru.savefood.it;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.env.MockEnvironment;
import ru.savefood.admin.AdminController;
import ru.savefood.audit.AuditService;
import ru.savefood.esg.EsgService;
import ru.savefood.kyc.KycCrypto;
import ru.savefood.kyc.KycService;
import ru.savefood.photo.PhotoModerationService;
import ru.savefood.security.AuthArgumentResolver;
import ru.savefood.security.CurrentUser;
import ru.savefood.security.JwtService;
import ru.savefood.telegram.TelegramService;
import ru.savefood.upload.UploadService;
import ru.savefood.volunteer.AvailabilityService;
import ru.savefood.volunteer.RouteRevertService;
import ru.savefood.volunteer.VolunteerController;
import ru.savefood.volunteer.VolunteerRepository;
import ru.savefood.volunteer.VolunteerService;
import ru.savefood.web.GlobalExceptionHandler;
import ru.savefood.web.RateLimiter;
import ru.savefood.webhook.WebhookService;
/** Focused HTTP regression coverage for raw volunteer KYC document access. */
class VolunteerKycDocumentAuthorizationIT extends PostgresIT {
    private static final String JWT_SECRET =
        "volunteer-kyc-document-authorization-test-secret";
    @TempDir
    Path kycDir;
    private JwtService jwt;
    private MockMvc mvc;
    private VolunteerRepository volunteers;
    private AdminController admin;
    private int ownerId;
    @BeforeEach
    void wireController() throws Exception {
        jwt = new JwtService(JWT_SECRET);
        volunteers = new VolunteerRepository(jdbc);
        VolunteerController controller = new VolunteerController(
            volunteers, mock(VolunteerService.class), mock(RateLimiter.class), mock(UploadService.class),
            new KycCrypto("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", false, new MockEnvironment()),
            mock(KycService.class), mock(PhotoModerationService.class),
            mock(WebhookService.class), mock(TelegramService.class), jdbc, mock(AuditService.class),
            true, kycDir.toString(), kycDir.toString());
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(new AuthArgumentResolver(jwt, jdbc))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        admin = new AdminController(jdbc, volunteers, mock(EsgService.class), mock(AuditService.class),
            mock(RouteRevertService.class), mock(AvailabilityService.class), mock(TelegramService.class),
            kycDir.toString(), kycDir.toString());
        ownerId = insertVolunteer("Document owner");
        jdbc.update("UPDATE volunteers SET status = 'pending', document = ?, kyc_generation = ?, "
                + "kyc_score = ?, kyc_verdict = ?, kyc_notes = ?, kyc_checked_at = NOW() WHERE id = ?",
            "/volunteer_kyc/owner.png", "generation-a", 0.42, "review", "Document needs review", ownerId);
        Files.write(kycDir.resolve("owner.png"), "owner-document".getBytes(StandardCharsets.UTF_8));
    }
    @Test
    void ownerCanReadOwnDocumentWithoutStoreCaching() throws Exception {
        String ownerToken = tokenFor("owner", "volunteer", ownerId);
        mvc.perform(get("/volunteers/{id}/document", ownerId).header("Authorization", bearer(ownerToken)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().bytes("owner-document".getBytes(StandardCharsets.UTF_8)));
    }
    @Test
    void anotherVolunteerCannotReadTheDocument() throws Exception {
        int anotherVolunteer = insertVolunteer("Another volunteer");
        String token = tokenFor("another", "volunteer", anotherVolunteer);
        mvc.perform(get("/volunteers/{id}/document", ownerId).header("Authorization", bearer(token)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }
    @Test
    void ordinaryAdminCannotReadTheDocument() throws Exception {
        String adminToken = tokenFor("ordinary-admin", "admin", null);
        mvc.perform(get("/volunteers/{id}/document", ownerId).header("Authorization", bearer(adminToken)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }
    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mvc.perform(get("/volunteers/{id}/document", ownerId))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }
    @Test
    void adminQueueReturnsKycAnalysisWithoutRawDocumentData() {
        int adminUserId = insertUser("queue-admin", "admin", null);
        List<Map<String, Object>> queue = admin.listVolunteers("pending",
            new CurrentUser(adminUserId, "queue-admin", "admin", null));
        Map<String, Object> row = queue.stream().filter(v -> ownerId == ((Number) v.get("id")).intValue())
            .findFirst().orElseThrow();
        assertThat(row).containsEntry("kyc_verdict", "review")
            .containsEntry("kyc_notes", "Document needs review")
            .containsKey("kyc_score")
            .doesNotContainKeys("document", "kyc_generation");
    }
    private int insertUser(String username, String role, Integer relatedId) {
        return jdbc.queryForObject(
            "INSERT INTO users (username, hashed_password, role, related_id) VALUES (?, 'hash', ?, ?) RETURNING id",
            Integer.class, username, role, relatedId);
    }
    private String tokenFor(String username, String role, Integer relatedId) {
        int userId = insertUser(username, role, relatedId);
        return jwt.create(userId, username, role, relatedId);
    }
    private static String bearer(String token) {
        return "Bearer " + token;
    }
}

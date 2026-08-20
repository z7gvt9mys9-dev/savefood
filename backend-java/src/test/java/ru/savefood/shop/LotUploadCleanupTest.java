package ru.savefood.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class LotUploadCleanupTest {

    @TempDir
    Path uploadDir;

    @Test
    void failedCleanupIsQueuedAndRetriedWithoutTouchingOtherFiles() throws Exception {
        String filename = "0123456789abcdef0123456789abcdef.png";
        Path blocked = Files.createDirectory(uploadDir.resolve(filename));
        Files.write(blocked.resolve("child"), new byte[] {1});
        Path unrelated = Files.write(uploadDir.resolve("unrelated.txt"), new byte[] {2});
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LotUploadCleanup cleanup = new LotUploadCleanup(jdbc, uploadDir.toString());

        cleanup.removeOrQueue(List.of(filename));

        assertThat(blocked).exists();
        assertThat(unrelated).exists();
        verify(jdbc).update(contains("INSERT INTO shop_upload_cleanup"), eq(filename));

        Files.delete(blocked.resolve("child"));
        when(jdbc.queryForList(contains("FROM shop_upload_cleanup")))
            .thenReturn(List.of(Map.of("id", 1L, "filename", filename)));
        cleanup.retryPending();

        assertThat(blocked).doesNotExist();
        assertThat(unrelated).exists();
        verify(jdbc).update(contains("completed_at = CURRENT_TIMESTAMP"), eq(1L));
    }
}

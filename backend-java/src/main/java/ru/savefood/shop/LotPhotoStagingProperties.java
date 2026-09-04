package ru.savefood.shop;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
/** Central bounds for short-lived lot photos staged before JSON lot creation. */
@Component
@ConfigurationProperties(prefix = "savefood.lot-photo-staging")
public class LotPhotoStagingProperties {
    private Duration ttl = Duration.ofMinutes(45);
    private int maxPendingCount = 10;
    private long maxPendingBytes = 25L * 1024 * 1024;
    private int cleanupBatchSize = 100;
    private int uploadRatePerMinute = 20;
    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }
    public int getMaxPendingCount() { return maxPendingCount; }
    public void setMaxPendingCount(int maxPendingCount) { this.maxPendingCount = maxPendingCount; }
    public long getMaxPendingBytes() { return maxPendingBytes; }
    public void setMaxPendingBytes(long maxPendingBytes) { this.maxPendingBytes = maxPendingBytes; }
    public int getCleanupBatchSize() { return cleanupBatchSize; }
    public void setCleanupBatchSize(int cleanupBatchSize) { this.cleanupBatchSize = cleanupBatchSize; }
    public int getUploadRatePerMinute() { return uploadRatePerMinute; }
    public void setUploadRatePerMinute(int uploadRatePerMinute) {
        this.uploadRatePerMinute = uploadRatePerMinute;
    }
    @PostConstruct
    void validate() {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || maxPendingCount < 1
                || maxPendingBytes < 1 || cleanupBatchSize < 1 || uploadRatePerMinute < 1) {
            throw new IllegalArgumentException("Invalid savefood.lot-photo-staging limits");
        }
    }
}

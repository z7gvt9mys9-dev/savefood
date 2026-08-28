package ru.savefood.webhook;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Central limits for outgoing partner webhooks. */
@Component
@ConfigurationProperties(prefix = "savefood.webhook")
public class WebhookProperties {
    private int workerCount = 8;
    private int queueCapacity = 100;
    private Duration requestTimeout = Duration.ofSeconds(10);
    private int maxRetries = 2;
    private Duration initialBackoff = Duration.ofMillis(100);
    private int maxPerShop = 20;
    private int maxInFlightPerShop = 2;

    public int getWorkerCount() { return workerCount; }
    public void setWorkerCount(int workerCount) { this.workerCount = workerCount; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public Duration getInitialBackoff() { return initialBackoff; }
    public void setInitialBackoff(Duration initialBackoff) { this.initialBackoff = initialBackoff; }
    public int getMaxPerShop() { return maxPerShop; }
    public void setMaxPerShop(int maxPerShop) { this.maxPerShop = maxPerShop; }
    public int getMaxInFlightPerShop() { return maxInFlightPerShop; }
    public void setMaxInFlightPerShop(int maxInFlightPerShop) { this.maxInFlightPerShop = maxInFlightPerShop; }

    void validate() {
        if (workerCount < 1 || queueCapacity < 1 || requestTimeout == null || requestTimeout.isNegative()
            || requestTimeout.isZero() || maxRetries < 0 || initialBackoff == null
            || initialBackoff.isNegative() || maxPerShop < 1 || maxInFlightPerShop < 1
            || (workerCount > 1 && maxInFlightPerShop >= workerCount)) {
            throw new IllegalArgumentException("Invalid savefood.webhook limits");
        }
    }
}

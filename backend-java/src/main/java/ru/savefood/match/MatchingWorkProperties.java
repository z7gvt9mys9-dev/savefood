package ru.savefood.match;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Limits for best-effort lot matching and its downstream delivery. */
@Component
@ConfigurationProperties(prefix = "savefood.matching")
public class MatchingWorkProperties {
    private final ExecutorLimits executor = new ExecutorLimits(2, 64);
    private final ExecutorLimits telegram = new ExecutorLimits(4, 128);
    private final ExecutorLimits push = new ExecutorLimits(4, 128);
    public ExecutorLimits getExecutor() { return executor; }
    public ExecutorLimits getTelegram() { return telegram; }
    public ExecutorLimits getPush() { return push; }
    private int recipientCandidates = 200;
    public int getRecipientCandidates() { return recipientCandidates; }
    public void setRecipientCandidates(int value) {
        if (value < 1) throw new IllegalArgumentException("recipientCandidates must be positive");
        recipientCandidates = value;
    }
    private int volunteerCandidates = 200;
    public int getVolunteerCandidates() { return volunteerCandidates; }
    public void setVolunteerCandidates(int value) {
        if (value < 1) throw new IllegalArgumentException("volunteerCandidates must be positive");
        volunteerCandidates = value;
    }
    private int recipientsNotified = 20;
    public int getRecipientsNotified() { return recipientsNotified; }
    public void setRecipientsNotified(int value) {
        if (value < 1) throw new IllegalArgumentException("recipientsNotified must be positive");
        recipientsNotified = value;
    }
    private int volunteersNotified = 10;
    public int getVolunteersNotified() { return volunteersNotified; }
    public void setVolunteersNotified(int value) {
        if (value < 1) throw new IllegalArgumentException("volunteersNotified must be positive");
        volunteersNotified = value;
    }
    private int telegramSends = 30;
    public int getTelegramSends() { return telegramSends; }
    public void setTelegramSends(int value) {
        if (value < 1) throw new IllegalArgumentException("telegramSends must be positive");
        telegramSends = value;
    }
    private int pushSends = 100;
    public int getPushSends() { return pushSends; }
    public void setPushSends(int value) {
        if (value < 1) throw new IllegalArgumentException("pushSends must be positive");
        pushSends = value;
    }
    private int subscriptionsPerChannel = 5;
    public int getSubscriptionsPerChannel() { return subscriptionsPerChannel; }
    public void setSubscriptionsPerChannel(int value) {
        if (value < 1) throw new IllegalArgumentException("subscriptionsPerChannel must be positive");
        subscriptionsPerChannel = value;
    }
    private int partnerCreatesPerMinute = 30;
    public int getPartnerCreatesPerMinute() { return partnerCreatesPerMinute; }
    public void setPartnerCreatesPerMinute(int value) {
        if (value < 1) throw new IllegalArgumentException("partnerCreatesPerMinute must be positive");
        partnerCreatesPerMinute = value;
    }
    private int candidateQueryTimeoutSeconds = 5;
    public int getCandidateQueryTimeoutSeconds() { return candidateQueryTimeoutSeconds; }
    public void setCandidateQueryTimeoutSeconds(int value) {
        if (value < 1) throw new IllegalArgumentException("Candidate query timeout must be positive");
        candidateQueryTimeoutSeconds = value;
    }
    public static class ExecutorLimits {
        private int workers;
        private int queueCapacity;
        private BoundedWorkExecutor.Rejection rejection = BoundedWorkExecutor.Rejection.DROP_NEWEST;
        public ExecutorLimits(int workers, int queueCapacity) {
            this.workers = workers;
            this.queueCapacity = queueCapacity;
        }
        public int getWorkers() { return workers; }
        public void setWorkers(int value) { workers = value; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int value) { queueCapacity = value; }
        public BoundedWorkExecutor.Rejection getRejection() { return rejection; }
        public void setRejection(BoundedWorkExecutor.Rejection value) { rejection = value; }
    }
}

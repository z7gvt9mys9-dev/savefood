package ru.savefood.match;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MatchingWorkConfiguration {
    @Bean(destroyMethod = "close")
    public BoundedWorkExecutor matchingExecutor(MatchingWorkProperties limits) {
        return new BoundedWorkExecutor("needs-match", limits.getExecutor());
    }
    @Bean(destroyMethod = "close")
    public BoundedWorkExecutor matchingTelegramExecutor(MatchingWorkProperties limits) {
        return new BoundedWorkExecutor("matching-telegram", limits.getTelegram());
    }
    @Bean(destroyMethod = "close")
    public BoundedWorkExecutor pushDispatchExecutor(MatchingWorkProperties limits) {
        return new BoundedWorkExecutor("push-dispatch", limits.getPush());
    }
}

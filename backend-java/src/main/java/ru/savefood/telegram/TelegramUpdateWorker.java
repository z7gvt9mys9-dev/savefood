package ru.savefood.telegram;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Uses the existing scheduler; no webhook executor or unbounded update threads. */
@Service
public class TelegramUpdateWorker {
    private static final Logger log = Logger.getLogger(TelegramUpdateWorker.class.getName());
    private final TelegramUpdateInbox inbox;
    private final TelegramBotService bot;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int batchSize;

    public TelegramUpdateWorker(TelegramUpdateInbox inbox, TelegramBotService bot,
            @Value("${savefood.telegram.inbox-batch-size:10}") int batchSize) {
        this.inbox = inbox;
        this.bot = bot;
        this.batchSize = Math.max(1, Math.min(100, batchSize));
    }

    @Scheduled(fixedDelayString = "${savefood.telegram.inbox-poll-ms:1000}")
    public void drain() {
        try {
            for (int i = 0; i < batchSize; i++) {
                TelegramUpdateInbox.Claim claim = inbox.claim();
                if (claim == null) return;
                inbox.process(claim, payload -> {
                    try {
                        bot.processUpdate(mapper.readTree(payload));
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException("Invalid stored Telegram update", e);
                    }
                });
            }
        } catch (RuntimeException e) {
            // The next scheduled poll recovers database outages; never log payloads or login tokens.
            log.warning("[telegram-inbox] polling failed: " + e.getClass().getSimpleName());
        }
    }
}

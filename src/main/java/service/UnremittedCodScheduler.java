package service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "reconciler.cod-detection.enabled", havingValue = "true")
public class UnremittedCodScheduler {

    private final service.UnremittedCodDetectionService service;

    @Scheduled(cron = "${reconciler.cod-detection.cron:0 30 2 * * *}", zone = "${reconciler.business-zone:Asia/Kolkata}")
    public void run() {
        try {
            service.detect();
        } catch (RuntimeException e) {
            log.error("Scheduled COD detection failed", e);
        }
    }
}

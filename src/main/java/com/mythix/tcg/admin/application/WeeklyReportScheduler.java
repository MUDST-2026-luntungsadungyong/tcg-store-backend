package com.mythix.tcg.admin.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Configuration
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.reports.weekly.enabled", havingValue = "true")
public class WeeklyReportScheduler {
    private final WeeklyReportService reports;

    @Scheduled(cron = "0 59 23 * * SUN", zone = "UTC")
    public void weekly() { generate(); }

    // Completes the cut-off after a grace minute and catches up after downtime.
    @Scheduled(fixedDelayString = "${app.reports.weekly.reconcile-ms:60000}", initialDelay = 60000)
    public void reconcile() { generate(); }

    private void generate() {
        try {
            int count = reports.generateDue(LocalDateTime.now(ZoneOffset.UTC));
            if (count > 0) log.info("Generated {} admin weekly reports", count);
        } catch (RuntimeException exception) {
            log.error("Weekly reporting failed; will retry on next reconciliation", exception);
        }
    }
}

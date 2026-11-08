package com.mythix.tcg.auction.application;

import com.mythix.tcg.auction.domain.Auction;
import com.mythix.tcg.auction.persistence.AuctionRepository;
import com.mythix.tcg.auction.api.AuctionLiveUpdates;
import org.springframework.context.ApplicationEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@Profile("!migration & !tidb")
@ConditionalOnProperty(name = "auction.lifecycle.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class AuctionLifecycle {
    private final AuctionRepository repository;
    private final ApplicationEventPublisher events;
    private final AuctionService service;


    @Scheduled(fixedDelayString = "${auction.lifecycle.interval-ms:5000}")
    public void runDueAuctions() {
        Instant now = Instant.now();
        for (Long id : repository.findDueIds(Auction.Status.SCHEDULED, Auction.Status.ACTIVE, now,
            PageRequest.ofSize(100))) {
            try {
                service.advanceLifecycle(id, now);
                events.publishEvent(new AuctionLiveUpdates.Changed(id));
            } catch (RuntimeException exception) {
                log.error("Could not advance auction {}", id, exception);
            }
        }
    }
}

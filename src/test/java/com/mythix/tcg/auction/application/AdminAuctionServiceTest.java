package com.mythix.tcg.auction.application;

import com.mythix.tcg.admin.persistence.AuditLogRepository;
import com.mythix.tcg.auction.domain.Auction;
import com.mythix.tcg.auction.persistence.AuctionBidRepository;
import com.mythix.tcg.auction.persistence.AuctionRepository;
import com.mythix.tcg.notification.application.NotificationService;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminAuctionServiceTest {
    @Test
    void pauseResumePreservesRemainingTimeAndRejectsRepeatedPause() {
        var auctions = mock(AuctionRepository.class);
        var bids = mock(AuctionBidRepository.class);
        var auctionService = mock(AuctionService.class);
        var users = mock(UserRepository.class);
        var audit = mock(AuditLogRepository.class);
        var notifications = mock(NotificationService.class);
        var events = mock(ApplicationEventPublisher.class);
        var admin = new User(); admin.setRole("ADMIN");
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(users.findAllById(any())).thenReturn(List.of());
        var auction = new Auction(); auction.setId(9L); auction.setSellerId(2L);
        auction.setStatus(Auction.Status.ACTIVE); auction.setEndAt(Instant.now().plusSeconds(3600));
        when(auctionService.lockedAuction(9L)).thenReturn(auction);
        when(bids.findAllByAuctionIdOrderByCreatedAtDescIdDesc(9L)).thenReturn(List.of());
        when(bids.findTop2ByAuctionIdOrderByCreatedAtDescIdDesc(9L)).thenReturn(List.of());
        var service = new AdminAuctionService(auctions,bids,auctionService,users,audit,notifications,events);
        Instant originalEnd = auction.getEndAt();
        service.act(1L,9L,"pause","investigate");
        assertEquals(Auction.Status.PAUSED, auction.getStatus());
        assertThrows(AuctionException.class, () -> service.act(1L,9L,"pause","again"));
        auction.setPausedAt(Instant.now().minusSeconds(120));
        service.act(1L,9L,"resume","cleared");
        assertEquals(Auction.Status.ACTIVE, auction.getStatus());
        assertNull(auction.getPausedAt());
        assertTrue(Duration.between(originalEnd, auction.getEndAt()).toSeconds() >= 120);
        verify(audit, times(2)).save(any());
    }

    @Test
    void liveFeedFlagsRecentDoublingAndBidderStrikes() {
        var auctions = mock(AuctionRepository.class);
        var bids = mock(AuctionBidRepository.class);
        var auctionService = mock(AuctionService.class);
        var users = mock(UserRepository.class);
        var admin = new User();
        admin.setRole("ADMIN");
        var bidder = new User();
        bidder.setAuctionStrikes(1);
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(users.findById(7L)).thenReturn(Optional.of(bidder));
        var auction = new Auction();
        auction.setId(9L);
        auction.setStatus(Auction.Status.ACTIVE);
        when(auctions.findAllByStatusIn(any(), any())).thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(auction)));
        Instant now = Instant.now();
        var newest = com.mythix.tcg.auction.domain.AuctionBid.builder().bidderId(7L)
            .bidAmount(new java.math.BigDecimal("200")).createdAt(now).build();
        var previous = com.mythix.tcg.auction.domain.AuctionBid.builder().bidderId(8L)
            .bidAmount(new java.math.BigDecimal("100")).createdAt(now.minusSeconds(60)).build();
        when(bids.findTop2ByAuctionIdOrderByCreatedAtDescIdDesc(9L)).thenReturn(List.of(newest, previous));
        var service = new AdminAuctionService(auctions, bids, auctionService, users,
            mock(AuditLogRepository.class), mock(NotificationService.class), mock(ApplicationEventPublisher.class));
        var flagged = service.list(1L, 0, 20).getContent().get(0);
        assertTrue(flagged.highRiskUser());
        assertTrue(flagged.priceAnomaly());
        previous.setCreatedAt(now.minusSeconds(301));
        assertFalse(service.list(1L, 0, 20).getContent().get(0).priceAnomaly());
    }
}

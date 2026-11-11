package com.mythix.tcg.auction.application;

import com.mythix.tcg.admin.persistence.AuditLogRepository;
import com.mythix.tcg.auction.domain.Auction;
import com.mythix.tcg.auction.domain.AuctionDispute;
import com.mythix.tcg.auction.domain.AuctionDisputeEvidence;
import com.mythix.tcg.auction.persistence.AuctionDisputeEvidenceRepository;
import com.mythix.tcg.auction.persistence.AuctionDisputeRepository;
import com.mythix.tcg.auction.persistence.AuctionRepository;
import com.mythix.tcg.notification.application.NotificationService;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuctionDisputeServiceTest {
    private final AuctionRepository auctions = mock(AuctionRepository.class);
    private final AuctionDisputeRepository disputes = mock(AuctionDisputeRepository.class);
    private final AuctionDisputeEvidenceRepository evidence = mock(AuctionDisputeEvidenceRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AuditLogRepository audit = mock(AuditLogRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final AuctionDisputeService service = new AuctionDisputeService(auctions, disputes, evidence, orders,
        users, audit, notifications);

    @Test
    void onlyWinningBuyerCanOpenAndReceiptTimestampMustBeWithinThreeDays() {
        var auction = new Auction();
        auction.setWinnerId(7L);
        auction.setOrderId(8L);
        when(auctions.findByIdForUpdate(9L)).thenReturn(Optional.of(auction));
        assertThrows(AuctionException.class, () -> service.submit(3L, 9L, "damage", List.of()));
        var order = new Order();
        order.setStatus("FULFILLED");
        when(orders.findById(8L)).thenReturn(Optional.of(order));
        assertThrows(AuctionException.class, () -> service.submit(7L, 9L, "damage", List.of()));
        auction.setReceivedAt(Instant.now().minus(4, ChronoUnit.DAYS));
        assertThrows(AuctionException.class, () -> service.submit(7L, 9L, "damage", List.of()));
        verify(disputes, never()).save(any());
    }

    @Test
    void bothParticipantsCanReadEvidenceButOtherUsersCannot() {
        var dispute = new AuctionDispute();
        dispute.setId(4L);
        dispute.setBuyerId(7L);
        dispute.setSellerId(8L);
        when(disputes.findById(4L)).thenReturn(Optional.of(dispute));
        var file = new AuctionDisputeEvidence();
        when(evidence.findByIdAndDisputeId(5L, 4L)).thenReturn(Optional.of(file));
        assertSame(file, service.file(7L, 4L, 5L, false));
        assertSame(file, service.file(8L, 4L, 5L, false));
        assertThrows(AuctionException.class, () -> service.file(9L, 4L, 5L, false));
    }

    @Test
    void terminalDecisionIsIdempotentAndCannotBeReplaced() {
        var admin = new User();
        admin.setRole("ADMIN");
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        var dispute = new AuctionDispute();
        dispute.setId(4L);
        dispute.setStatus("PENDING");
        dispute.setAuctionId(9L);
        dispute.setBuyerId(7L);
        dispute.setSellerId(8L);
        when(disputes.lock(4L)).thenReturn(Optional.of(dispute));
        when(evidence.findRefs(4L)).thenReturn(List.of());
        service.decide(1L, 4L, "DISMISS", "insufficient evidence");
        service.decide(1L, 4L, "DISMISS", "retry");
        assertThrows(AuctionException.class, () -> service.decide(1L, 4L, "RETURN", "change"));
        verify(audit, times(1)).save(any());
    }
}

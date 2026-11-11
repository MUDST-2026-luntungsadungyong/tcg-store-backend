package com.mythix.tcg.auction.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mythix.tcg.admin.domain.AuditLog;
import com.mythix.tcg.admin.persistence.AuditLogRepository;
import com.mythix.tcg.auction.domain.AuctionBid;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuctionSecurityAuditTest {
    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }
    @Test void recordsBidAndUsesSocketPeerInsteadOfUntrustedForwardedHeader() throws Exception {
        var logs = mock(AuditLogRepository.class);
        var users = mock(UserRepository.class);
        var actor = User.builder().id(7L).build();
        when(users.findById(7L)).thenReturn(Optional.of(actor));
        var mapper = new ObjectMapper();
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.4");
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        request.addHeader("User-Agent", "x".repeat(600));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var bid = AuctionBid.builder().auctionId(12L).bidderId(7L).bidAmount(new BigDecimal("100.00"))
            .bidType(AuctionBid.BidType.MANUAL).createdAt(Instant.parse("2026-01-01T00:00:00Z")).build();
        new AuctionSecurityAudit(logs, users).bid(bid);
        var captured = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
        verify(logs).save(captured.capture());
        var event = captured.getValue();
        var details = mapper.readTree(event.getDetails());
        assertThat(event.getActor()).isSameAs(actor);
        assertThat(event.getTargetId()).isEqualTo("12");
        assertThat(details.path("amount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(details.path("clientIp").asText()).isEqualTo("192.0.2.4");
        assertThat(details.path("userAgent").asText()).hasSize(512);
        assertThat(details.path("bidTimestamp").asText()).isEqualTo("2026-01-01T00:00:00Z");
    }
}

package com.mythix.tcg.auction.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mythix.tcg.admin.domain.AuditLog;
import com.mythix.tcg.admin.persistence.AuditLogRepository;
import com.mythix.tcg.auction.domain.AuctionBid;
import com.mythix.tcg.user.persistence.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

/** Writes auction events in the same transaction as the state change. */
@Service
@RequiredArgsConstructor
public class AuctionSecurityAudit {
    private final AuditLogRepository logs;
    private final UserRepository users;
    private final ObjectMapper mapper = new ObjectMapper();

    public void record(String action, Long auctionId, Long actorId, Map<String, ?> fields) {
        Map<String, Object> details = new LinkedHashMap<>(fields);
        details.put("actorId", actorId);
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            // Forwarded headers are client-controlled unless a trusted proxy is configured.
            details.put("clientIp", bounded(request.getRemoteAddr(), 64));
            details.put("userAgent", bounded(request.getHeader("User-Agent"), 512));
        }
        try {
            logs.save(AuditLog.builder().actor(users.findById(actorId).orElseThrow())
                .action(action).targetType("AUCTION").targetId(auctionId.toString())
                .details(mapper.writeValueAsString(details)).build());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize auction audit event", e);
        }
    }

    public void bid(AuctionBid bid) {
        record("AUCTION_BID", bid.getAuctionId(), bid.getBidderId(), Map.of(
            "bidderId", bid.getBidderId(), "amount", bid.getBidAmount(),
            "bidType", bid.getBidType().name(), "bidTimestamp", bid.getCreatedAt().toString()));
    }

    private static String bounded(String value, int limit) {
        if (value == null) return "";
        return value.substring(0, Math.min(value.length(), limit));
    }
}

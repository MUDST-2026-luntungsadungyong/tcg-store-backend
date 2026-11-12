package com.mythix.tcg.auction.api;

import com.mythix.tcg.auction.domain.Auction;
import com.mythix.tcg.auction.persistence.AuctionRepository;
import com.mythix.tcg.auth.security.JwtService;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "auction.lifecycle.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAuctionDisputeIntegrationTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private CardRepository cards;
    @Autowired private ProductRepository products;
    @Autowired private AuctionRepository auctions;
    @Autowired private OrderRepository orders;
    @Autowired private com.mythix.tcg.auction.application.AuctionService auctionService;
    private AuctionApiFixtures fixtures;
    private User buyer;
    private User seller;
    private User other;
    private User admin;
    private Product product;

    @BeforeEach
    void setup() {
        fixtures = new AuctionApiFixtures(jwt, users, cards, products);
        buyer = fixtures.user("USER", "NONE");
        seller = fixtures.user("USER", "APPROVED");
        other = fixtures.user("USER", "NONE");
        admin = fixtures.user("ADMIN", "NONE");
        product = fixtures.product(seller, 1, "SHOW");
    }

    private Auction auction(Auction.Status state) {
        Instant now = Instant.now();
        var auction = new Auction();
        auction.setSellerId(seller.getId());
        auction.setProductId(product.getId());
        auction.setStatus(state);
        auction.setCardId(product.getCard().getId());
        auction.setCardName(product.getCard().getName());
        auction.setCondition("NM");
        auction.setStartPrice(new BigDecimal("100"));
        auction.setBuyItNowPrice(new BigDecimal("500"));
        auction.setStartMode(Auction.StartMode.NOW);
        auction.setStartAt(now.minusSeconds(60));
        auction.setEndAt(now.plus(1, ChronoUnit.HOURS));
        auction.setDurationHours(24);
        auction.setCreatedAt(now);
        auction.setUpdatedAt(now);
        return auction;
    }

    private Auction received(Instant receivedAt) {
        Order order = Order.builder().buyer(buyer).orderNumber("dispute-" + UUID.randomUUID())
            .orderType("AUCTION").status("FULFILLED").totalAmount(BigDecimal.TEN).build();
        order = orders.saveAndFlush(order);
        Auction auction = auction(Auction.Status.COMPLETED);
        auction.setWinnerId(buyer.getId());
        auction.setOrderId(order.getId());
        auction.setReceivedAt(receivedAt);
        return auctions.saveAndFlush(auction);
    }

    @Test
    void participantEvidenceIsPrivateAndDecisionsAreIdempotent() throws Exception {
        Auction auction = received(Instant.now().minus(71, ChronoUnit.HOURS));
        mvc.perform(multipart("/api/auctions/{id}/disputes", auction.getId())
            .file(AuctionApiFixtures.image("evidence")).param("reason", "Damaged")
            .header("Authorization", fixtures.bearer(other))).andExpect(status().isNotFound());
        var result = mvc.perform(multipart("/api/auctions/{id}/disputes", auction.getId())
            .file(AuctionApiFixtures.image("evidence")).param("reason", "Damaged")
            .header("Authorization", fixtures.bearer(buyer))).andExpect(status().isOk()).andReturn();
        var caseJson = json.readTree(result.getResponse().getContentAsString());
        long id = caseJson.path("id").asLong();
        assertThat(caseJson.path("evidence").get(0).path("submittedBy").asLong()).isEqualTo(buyer.getId());
        assertThat(caseJson.path("evidence").get(0).path("createdAt").asText()).isNotBlank();
        String evidenceUrl = caseJson.path("evidenceUrls").get(0).asText();
        mvc.perform(get("/api/auction-disputes/{id}", id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auction-disputes/{id}", id).header("Authorization", fixtures.bearer(other)))
            .andExpect(status().isNotFound());
        mvc.perform(get(evidenceUrl).header("Authorization", fixtures.bearer(seller))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "private, no-store"));
        mvc.perform(get(evidenceUrl).header("Authorization", fixtures.bearer(other))).andExpect(status().isNotFound());
        mvc.perform(multipart("/api/auction-disputes/{id}/evidence", id)
            .file(AuctionApiFixtures.image("evidence")).header("Authorization", fixtures.bearer(seller)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.evidence[1].submittedBy").value(seller.getId()))
            .andExpect(jsonPath("$.evidence[1].createdAt").isNotEmpty());
        mvc.perform(get("/api/admin/auction-disputes").header("Authorization", fixtures.bearer(buyer)))
            .andExpect(status().isForbidden());
        String decision = "{\"decision\":\"DISMISS\",\"reason\":\"Reviewed\"}";
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/admin/auction-disputes/{id}/decide", id)
                .header("Authorization", fixtures.bearer(admin)).contentType(MediaType.APPLICATION_JSON).content(decision))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISMISS"));
        }
        mvc.perform(get("/api/auction-disputes/{id}", id).header("Authorization", fixtures.bearer(seller)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.decisionReason").value("Reviewed"));
    }

    @Test
    void lateAndInvalidEvidenceAreRejected() throws Exception {
        Auction expired = received(Instant.now().minus(73, ChronoUnit.HOURS));
        mvc.perform(multipart("/api/auctions/{id}/disputes", expired.getId())
            .file(AuctionApiFixtures.image("evidence")).param("reason", "Damage")
            .header("Authorization", fixtures.bearer(buyer))).andExpect(status().isConflict());
        Auction fresh = received(Instant.now());
        var invalid = new MockMultipartFile("evidence", "bad.png", "image/png", new byte[]{1, 2, 3});
        mvc.perform(multipart("/api/auctions/{id}/disputes", fresh.getId()).file(invalid)
            .param("reason", "Damage").header("Authorization", fixtures.bearer(buyer)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void schedulerLeavesExpiredPausedAuctionUntouched() {
        product.setAuctionReserved(1);
        products.saveAndFlush(product);
        Auction paused = auction(Auction.Status.PAUSED);
        paused.setEndAt(Instant.now().minusSeconds(60));
        paused.setPausedAt(Instant.now().minusSeconds(120));
        paused.setStockHeld(true);
        paused = auctions.saveAndFlush(paused);
        auctionService.advanceLifecycle(paused.getId(), Instant.now());
        assertThat(auctions.findById(paused.getId()).orElseThrow().getStatus()).isEqualTo(Auction.Status.PAUSED);
        assertThat(products.findById(product.getId()).orElseThrow().getAuctionReserved()).isEqualTo(1);
    }
    @Test
    void pausedAuctionRejectsBidAndBuyoutAndForceCancellationReleasesHold() throws Exception {
        product.setAuctionReserved(1);
        products.saveAndFlush(product);
        Auction active = auction(Auction.Status.ACTIVE);
        active.setStockHeld(true);
        active = auctions.saveAndFlush(active);
        long id = active.getId();
        mvc.perform(post("/api/admin/auctions/{id}/pause", id).header("Authorization", fixtures.bearer(admin))
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"investigate\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAUSED"));
        mvc.perform(get("/api/auctions/{id}", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAUSED"))
            .andExpect(jsonPath("$.pausedAt").isNotEmpty());
        mvc.perform(post("/api/auctions/{id}/bids", id).header("Authorization", fixtures.bearer(buyer))
            .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":150}"))
            .andExpect(status().isConflict());
        mvc.perform(post("/api/auctions/{id}/buyout", id).header("Authorization", fixtures.bearer(buyer)))
            .andExpect(status().isConflict());
        mvc.perform(post("/api/admin/auctions/{id}/force-cancel", id).header("Authorization", fixtures.bearer(admin))
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"fraud\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(products.findById(product.getId()).orElseThrow().getAuctionReserved()).isZero();
    }
}

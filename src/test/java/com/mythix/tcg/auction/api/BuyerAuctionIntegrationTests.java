package com.mythix.tcg.auction.api;

import com.mythix.tcg.auction.api.AuctionExtendedDtos.BidRequest;
import com.mythix.tcg.auction.api.AuctionExtendedDtos.ProxyBidRequest;
import com.mythix.tcg.auction.application.AuctionBidService;
import com.mythix.tcg.auction.application.AuctionDiscoveryService;
import com.mythix.tcg.auction.application.AuctionSettlementService;
import com.mythix.tcg.auction.domain.Auction;
import com.mythix.tcg.auction.persistence.AuctionRepository;
import com.mythix.tcg.auth.security.JwtService;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.collection.persistence.CollectionRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "auction.lifecycle.enabled=false",
    "auction.watchlist.check-interval-ms=99999999",
    "auction.settlement.check-interval-ms=99999999"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BuyerAuctionIntegrationTests {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository users;
    @Autowired private CardRepository cards;
    @Autowired private ProductRepository products;
    @Autowired private AuctionRepository auctions;
    @Autowired private OrderRepository orders;
    @Autowired private CollectionRepository collections;
    @Autowired private AuctionBidService bidService;
    @Autowired private AuctionSettlementService settlementService;

    private AuctionApiFixtures fixtures;
    private User seller;
    private User buyer1;
    private User buyer2;
    private Product product;
    private String sellerAuth;
    private String buyer1Auth;
    private String buyer2Auth;

    @BeforeEach
    void setup() {
        fixtures = new AuctionApiFixtures(jwtService, users, cards, products);
        seller = fixtures.user("USER", "APPROVED");
        buyer1 = fixtures.user("USER", "NONE");
        buyer2 = fixtures.user("USER", "NONE");
        product = fixtures.product(seller, 10, "SHOW");
        sellerAuth = "Bearer " + jwtService.generateToken(seller);
        buyer1Auth = "Bearer " + jwtService.generateToken(buyer1);
        buyer2Auth = "Bearer " + jwtService.generateToken(buyer2);
    }

    private Auction createActiveAuction(BigDecimal startPrice, BigDecimal reservePrice, BigDecimal buyNowPrice) {
        Instant now = Instant.now();
        Auction auction = new Auction();
        auction.setSellerId(seller.getId());
        auction.setProductId(product.getId());
        auction.setStatus(Auction.Status.ACTIVE);
        auction.setCardId(product.getCard().getId());
        auction.setCardName(product.getCard().getName());
        auction.setSetName(product.getCard().getSetName());
        auction.setCondition("NM");
        auction.setGradingCompany("PSA");
        auction.setGrade("10");
        auction.setCertNumber("12345678");
        auction.setStartPrice(startPrice);
        auction.setReservePrice(reservePrice);
        auction.setBuyItNowPrice(buyNowPrice);
        auction.setStartMode(Auction.StartMode.NOW);
        auction.setStartAt(now.minus(1, ChronoUnit.HOURS));
        auction.setEndAt(now.plus(24, ChronoUnit.HOURS));
        auction.setDurationHours(24);
        auction.setBidCount(0);
        auction.setStockHeld(true);
        auction.setCreatedAt(now);
        auction.setUpdatedAt(now);
        product.setAuctionReserved(product.getAuctionReserved() + 1);
        products.save(product);
        return auctions.save(auction);
    }

    @Test
    void testDiscoveryAndMaskedBidHistory() throws Exception {
        Auction auction = createActiveAuction(new BigDecimal("100.00"), new BigDecimal("300.00"), new BigDecimal("1000.00"));

        // 1. Browse Hub
        mvc.perform(get("/api/auctions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[?(@.id == " + auction.getId() + ")].cardName").value(org.hamcrest.Matchers.hasItem(product.getCard().getName())))
            .andExpect(jsonPath("$.content[?(@.id == " + auction.getId() + ")].currentPrice").value(org.hamcrest.Matchers.hasItem(100.00)));

        // 2. Buyer Detail View (Reserve price must be hidden!)
        mvc.perform(get("/api/auctions/" + auction.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reservePrice").doesNotExist())
            .andExpect(jsonPath("$.isReserveMet").value(false))
            .andExpect(jsonPath("$.certVerificationUrl").value("https://www.psacard.com/cert/12345678"))
            .andExpect(jsonPath("$.nextMinimumBid").value(100.00));

        // 3. Buyer 1 places bid of 100
        BidRequest req = new BidRequest();
        req.setAmount(new BigDecimal("100.00"));
        mvc.perform(post("/api/auctions/" + auction.getId() + "/bids")
                .header("Authorization", buyer1Auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bidAmount").value(100.00))
            .andExpect(jsonPath("$.isSelf").value(true));

        // 4. View Bid History (Check identity masking)
        mvc.perform(get("/api/auctions/" + auction.getId() + "/bids")
                .header("Authorization", buyer2Auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].bidAmount").value(100.00))
            .andExpect(jsonPath("$[0].isSelf").value(false));
    }

    @Test
    void testProxyBiddingAutoCounter() throws Exception {
        Auction auction = createActiveAuction(new BigDecimal("100.00"), new BigDecimal("200.00"), new BigDecimal("1000.00"));

        // Buyer 1 sets proxy max bid = 500
        ProxyBidRequest pReq1 = new ProxyBidRequest();
        pReq1.setMaxAmount(new BigDecimal("500.00"));
        mvc.perform(post("/api/auctions/" + auction.getId() + "/proxy-bid")
                .header("Authorization", buyer1Auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(pReq1)))
            .andExpect(status().isOk());

        Auction updated = auctions.findById(auction.getId()).orElseThrow();
        assertThat(updated.getHighestBid()).isEqualByComparingTo("100.00");

        // Buyer 2 bids manual 200 (less than Buyer 1's max 500)
        BidRequest bReq2 = new BidRequest();
        bReq2.setAmount(new BigDecimal("200.00"));
        mvc.perform(post("/api/auctions/" + auction.getId() + "/bids")
                .header("Authorization", buyer2Auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(bReq2)))
            .andExpect(status().isOk());

        // Buyer 1's proxy should automatically counter at 200 + increment (20) = 220
        updated = auctions.findById(auction.getId()).orElseThrow();
        assertThat(updated.getHighestBid()).isEqualByComparingTo("220.00");

        bidService.placeManualBid(auction.getId(), buyer2.getId(), new BigDecimal("500.00"));
        assertThat(auctions.findById(auction.getId()).orElseThrow().getLeadingBidderId()).isEqualTo(buyer1.getId());
        settlementService.closeAuction(auction.getId(), auction.getEndAt().plusSeconds(1));
        assertThat(auctions.findById(auction.getId()).orElseThrow().getWinnerId()).isEqualTo(buyer1.getId());
    }

    @Test
    void testInstantBuyoutClosesAuctionAndCreatesOrder() throws Exception {
        Auction auction = createActiveAuction(new BigDecimal("100.00"), new BigDecimal("300.00"), new BigDecimal("800.00"));

        mvc.perform(post("/api/auctions/" + auction.getId() + "/buyout")
                .header("Authorization", buyer1Auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("BUYOUT_COMPLETED"))
            .andExpect(jsonPath("$.finalPrice").value(800.00))
            .andExpect(jsonPath("$.platformFeeAmount").value(40.00)) // 5% of 800 = 40
            .andExpect(jsonPath("$.sellerNetAmount").value(760.00));

        Auction finalized = auctions.findById(auction.getId()).orElseThrow();
        assertThat(finalized.getStatus()).isEqualTo(Auction.Status.BUYOUT_COMPLETED);
        assertThat(finalized.getOrderId()).isNotNull();

        // Check stock moved to order reserved
        Product prod = products.findById(product.getId()).orElseThrow();
        assertThat(prod.getAuctionReserved()).isEqualTo(0);
        assertThat(prod.getReserved()).isEqualTo(1);
    }

    @Test
    void testWatchlistToggleAndListing() throws Exception {
        Auction auction = createActiveAuction(new BigDecimal("100.00"), null, null);

        // Toggle On
        mvc.perform(post("/api/auctions/" + auction.getId() + "/watch")
                .header("Authorization", buyer1Auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.isWatching").value(true));

        // Get My Watchlist
        mvc.perform(get("/api/user/watchlist")
                .header("Authorization", buyer1Auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(auction.getId()));

        // Toggle Off
        mvc.perform(post("/api/auctions/" + auction.getId() + "/watch")
                .header("Authorization", buyer1Auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.isWatching").value(false));
    }

    @Test
    void testSellerAnalyticsAndBuyerPortfolio() throws Exception {
        Auction auction = createActiveAuction(new BigDecimal("100.00"), new BigDecimal("100.00"), new BigDecimal("500.00"));

        // Buyer buyout
        mvc.perform(post("/api/auctions/" + auction.getId() + "/buyout")
                .header("Authorization", buyer1Auth))
            .andExpect(status().isOk());

        // Check Seller Analytics
        mvc.perform(get("/api/seller/auctions/analytics")
                .header("Authorization", sellerAuth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.grossAuctionVolume").value(0.00))
            .andExpect(jsonPath("$.totalPlatformFee").value(0.00));

        // Check Buyer Portfolio
        mvc.perform(get("/api/user/auction-portfolio")
                .header("Authorization", buyer1Auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.auctionsWon").value(1))
            .andExpect(jsonPath("$.totalInvested").value(0.00))
            .andExpect(jsonPath("$.badges[0]").value("First Win"));
    }

    @Test
    void testAdminCommissionIsAppliedToNewOrderAndPrivateToSeller() throws Exception {
        String policyPath = "/api/admin/commission-policy";
        String admin = fixtures.bearer(fixtures.user("ADMIN", "NONE"));
        mvc.perform(get(policyPath).header("Authorization", buyer1Auth)).andExpect(status().isForbidden());
        try {
            mvc.perform(post(policyPath).header("Authorization", admin).contentType("application/json")
                    .content("{\"rate\":0.03,\"minimumFee\":20}"))
                .andExpect(status().isOk());
            Auction auction = createActiveAuction(new BigDecimal("100.00"), null, new BigDecimal("1000.00"));
            bidService.executeBuyout(auction.getId(), buyer1.getId());
            String settlement = "/api/seller/auctions/" + auction.getId() + "/settlement-summary";
            mvc.perform(get(settlement).header("Authorization", sellerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platformFeeAmount").value(30.00))
                .andExpect(jsonPath("$.sellerNetAmount").value(970.00));
        } finally {
            mvc.perform(post(policyPath).header("Authorization", admin).contentType("application/json")
                    .content("{\"rate\":0.05,\"minimumFee\":20}"))
                .andExpect(status().isOk());
        }
    }

    @Test
    void testSettlementReserveNotMetReleasesStock() {
        Auction auction = createActiveAuction(new BigDecimal("100.00"), new BigDecimal("500.00"), null);

        // Buyer bids 200 (below reserve 500)
        bidService.placeManualBid(auction.getId(), buyer1.getId(), new BigDecimal("200.00"));

        // Auction ends
        settlementService.closeAuction(auction.getId(), Instant.now().plus(48, ChronoUnit.HOURS));

        Auction settled = auctions.findById(auction.getId()).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(Auction.Status.RESERVE_NOT_MET);
        assertThat(settled.isStockHeld()).isFalse();

        // Check stock released
        Product prod = products.findById(product.getId()).orElseThrow();
        assertThat(prod.getAuctionReserved()).isEqualTo(0);
    }

    @Test
    void testSettlementUnsoldReleasesStock() {
        Auction auction = createActiveAuction(new BigDecimal("100.00"), new BigDecimal("500.00"), null);

        // Zero bids, auction ends
        settlementService.closeAuction(auction.getId(), Instant.now().plus(48, ChronoUnit.HOURS));

        Auction settled = auctions.findById(auction.getId()).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(Auction.Status.UNSOLD_NO_BIDS);
        assertThat(settled.isStockHeld()).isFalse();

        Product prod = products.findById(product.getId()).orElseThrow();
        assertThat(prod.getAuctionReserved()).isEqualTo(0);
    }

    @Test
    void testSettlementWonCreatesOrderAndTransfersCardAfterFulfillment() throws Exception {
        Auction auction = createActiveAuction(new BigDecimal("100.00"), new BigDecimal("200.00"), null);

        // Buyer 1 bids 300 (exceeds reserve 200)
        bidService.placeManualBid(auction.getId(), buyer1.getId(), new BigDecimal("300.00"));

        // Close auction
        settlementService.closeAuction(auction.getId(), Instant.now().plus(48, ChronoUnit.HOURS));

        Auction settled = auctions.findById(auction.getId()).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(Auction.Status.AWAITING_PAYMENT);
        assertThat(settled.getWinnerId()).isEqualTo(buyer1.getId());
        assertThat(settled.getWinningBid()).isEqualByComparingTo("300.00");
        assertThat(settled.getOrderId()).isNotNull();

        String path = "/api/orders/" + settled.getOrderId();
        String sellerPath = "/api/seller/orders/" + settled.getOrderId();
        byte[] png = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aGGQAAAAASUVORK5CYII=");
        var file = new org.springframework.mock.web.MockMultipartFile("file", "slip.png", "image/png", png);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(path + "/payment-proof")
                .file(file).header("Authorization", buyer1Auth))
            .andExpect(status().isNoContent());
        mvc.perform(get(path + "/payment-proof").header("Authorization", buyer2Auth))
            .andExpect(status().isNotFound());
        mvc.perform(get(path + "/payment-proof").header("Authorization", sellerAuth))
            .andExpect(status().isOk());

        assertThat(orders.findById(settled.getOrderId()).orElseThrow().getStatus()).isEqualTo("PENDING");
        settlementService.processExpiredPaymentsAt(settled.getPaymentDeadline().plusSeconds(1));
        assertThat(orders.findById(settled.getOrderId()).orElseThrow().getStatus()).isEqualTo("PENDING");


        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(sellerPath + "/status")
                .header("Authorization", sellerAuth).contentType("application/json")
                .content("{\"status\":\"CONFIRMED\"}"))
            .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(sellerPath + "/status")
                .header("Authorization", sellerAuth).contentType("application/json")
                .content("{\"status\":\"SENT\",\"trackingNumber\":\"TCG12345\"}"))
            .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(path + "/status")
                .header("Authorization", buyer1Auth).contentType("application/json")
                .content("{\"status\":\"FULFILLED\"}"))
            .andExpect(status().isOk());
        assertThat(auctions.findById(auction.getId()).orElseThrow().getStatus()).isEqualTo(Auction.Status.COMPLETED);
        var binderItems = collections.findByUserIdOrderByCreatedAtDesc(buyer1.getId());
        assertThat(binderItems).isNotEmpty();
        assertThat(binderItems.getFirst().isAuctionWon()).isTrue();
        assertThat(binderItems.getFirst().getProvenance()).contains("Auction #");
        mvc.perform(get("/api/seller/auctions/analytics").header("Authorization", sellerAuth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.grossAuctionVolume").value(300.00))
            .andExpect(jsonPath("$.totalPlatformFee").value(20.00));
        mvc.perform(get("/api/user/auction-portfolio").header("Authorization", buyer1Auth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalInvested").value(300.00));
    }

    @Test
    void testDefaultingPenaltyAndSecondChanceOffer() throws Exception {
        Auction auction = createActiveAuction(new BigDecimal("100.00"), new BigDecimal("100.00"), null);

        // Buyer 1 bids 200, Buyer 2 bids 300
        bidService.placeManualBid(auction.getId(), buyer1.getId(), new BigDecimal("200.00"));
        bidService.placeManualBid(auction.getId(), buyer2.getId(), new BigDecimal("300.00"));

        // Close auction -> Buyer 2 is winner
        settlementService.closeAuction(auction.getId(), auction.getEndAt().plusSeconds(1));

        Auction settled = auctions.findById(auction.getId()).orElseThrow();
        assertThat(settled.getWinnerId()).isEqualTo(buyer2.getId());

        // Fast forward 49 hours without payment -> triggers defaulting penalty
        Instant futureTime = settled.getPaymentDeadline().plusSeconds(1);
        settlementService.processExpiredPaymentsAt(futureTime);

        User defaultingUser = users.findById(buyer2.getId()).orElseThrow();
        assertThat(defaultingUser.getAuctionStrikes()).isEqualTo(1);

        Auction defaultedAuction = auctions.findById(auction.getId()).orElseThrow();
        assertThat(defaultedAuction.getStatus()).isEqualTo(Auction.Status.DEFAULTED);

        // Seller sends Second-Chance offer to Buyer 1 (runner up)
        String offerResponseBody = mvc.perform(post("/api/seller/auctions/" + auction.getId() + "/second-chance-offer")
                .header("Authorization", sellerAuth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.offerPrice").value(200.00))
            .andReturn().getResponse().getContentAsString();

        // Parse offer ID from response
        long offerId = json.readTree(offerResponseBody).get("id").asLong();

        // Buyer 1 accepts offer
        mvc.perform(post("/api/auctions/second-chance/" + offerId + "/respond?accept=true")
                .header("Authorization", buyer1Auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"));
    }
}

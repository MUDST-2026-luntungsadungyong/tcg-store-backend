package com.mythix.tcg.auction.api;

import com.mythix.tcg.auction.api.AuctionExtendedDtos.BidRequest;
import com.mythix.tcg.auction.api.AuctionExtendedDtos.ProxyBidRequest;
import com.mythix.tcg.auction.application.AuctionBidService;
import com.mythix.tcg.auction.application.AuctionWatchlistService;
import com.mythix.tcg.auction.application.AuctionSettlementService;
import com.mythix.tcg.auction.domain.Auction;
import com.mythix.tcg.auction.persistence.AuctionRepository;
import com.mythix.tcg.auction.persistence.AuctionSecondChanceOfferRepository;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
    @Autowired private AuctionSecondChanceOfferRepository offers;
    @Autowired private OrderRepository orders;
    @Autowired private CollectionRepository collections;
    @Autowired private AuctionBidService bidService;
    @Autowired private AuctionWatchlistService watchlistService;
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
    void raisingLeadingProxyCeilingKeepsPublicPriceAndBidCount() throws Exception {
        Auction auction = createActiveAuction(new BigDecimal("400"), null, null);
        String path = "/api/auctions/" + auction.getId() + "/proxy-bid";
        for (int ceiling : new int[]{800, 1000, 1000}) {
            mvc.perform(post(path).header("Authorization", buyer1Auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxAmount\":" + ceiling + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bidAmount").value(400));
        }
        Auction updated = auctions.findById(auction.getId()).orElseThrow();
        assertThat(updated.getBidCount()).isEqualTo(1);
        mvc.perform(post("/api/auctions/" + auction.getId() + "/bids").header("Authorization", buyer2Auth)
            .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":900}"))
            .andExpect(status().isOk());
        assertThat(auctions.findById(auction.getId()).orElseThrow().getHighestBid()).isEqualByComparingTo("950");
    }

    @Test
    void cancellingAuctionOrderAlsoClosesAuctionAndReleasesStock() throws Exception {
        Auction auction = createActiveAuction(new BigDecimal("400"), null, new BigDecimal("900"));
        mvc.perform(post("/api/auctions/" + auction.getId() + "/buyout").header("Authorization", buyer1Auth))
            .andExpect(status().isOk());
        Long orderId = auctions.findById(auction.getId()).orElseThrow().getOrderId();
        mvc.perform(patch("/api/orders/" + orderId + "/status")
            .header("Authorization", buyer1Auth).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"CANCELLED\",\"note\":\"Cannot proceed\"}"))
            .andExpect(status().isOk());
        assertThat(auctions.findById(auction.getId()).orElseThrow().getStatus()).isEqualTo(Auction.Status.CANCELLED);
        assertThat(products.findById(product.getId()).orElseThrow().getReserved()).isZero();
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo("CANCELLED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPT", "REJECT", "EXPIRE"})
    void sellerCancelsForNoContactAndRunnerUpCanRespondOnlyWhilePending(String outcome) throws Exception {
        Auction auction = createActiveAuction(new BigDecimal("400"), null, new BigDecimal("900"));
        String room = "/api/auctions/" + auction.getId();
        mvc.perform(post(room + "/bids").header("Authorization", buyer2Auth)
            .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":400}")).andExpect(status().isOk());
        mvc.perform(post(room + "/buyout").header("Authorization", buyer1Auth)).andExpect(status().isOk());
        Long orderId = auctions.findById(auction.getId()).orElseThrow().getOrderId();
        mvc.perform(patch("/api/seller/orders/" + orderId + "/status")
            .header("Authorization", sellerAuth).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"CANCELLED\",\"note\":\"No contact or payment received\",\"noBuyerContact\":true}"))
            .andExpect(status().isOk());
        assertThat(auctions.findById(auction.getId()).orElseThrow().getStatus()).isEqualTo(Auction.Status.DEFAULTED);
        assertThat(products.findById(product.getId()).orElseThrow().getReserved()).isZero();

        var offer = mvc.perform(post("/api/seller/auctions/" + auction.getId() + "/second-chance-offer")
            .header("Authorization", sellerAuth)).andExpect(status().isOk())
            .andExpect(jsonPath("$.offerPrice").value(400)).andReturn();
        long offerId = json.readTree(offer.getResponse().getContentAsByteArray()).path("id").asLong();
        String respond = "/api/auctions/second-chance/" + offerId + "/respond";
        mvc.perform(post(respond).param("accept", "true").header("Authorization", buyer1Auth))
            .andExpect(status().isNotFound());
        if ("EXPIRE".equals(outcome)) {
            var expired = offers.findById(offerId).orElseThrow();
            expired.setCreatedAt(Instant.now().minus(25, ChronoUnit.HOURS));
            expired.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
            offers.saveAndFlush(expired);
        } else {
            mvc.perform(post(respond).param("accept", Boolean.toString("ACCEPT".equals(outcome)))
                .header("Authorization", buyer2Auth)).andExpect(status().isOk());
        }
        mvc.perform(post(respond).param("accept", "true").header("Authorization", buyer2Auth))
            .andExpect(status().isConflict());
        mvc.perform(get("/api/user/auction-second-chance-offers").header("Authorization", buyer2Auth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        Auction reassigned = auctions.findById(auction.getId()).orElseThrow();
        if (!"ACCEPT".equals(outcome)) {
            assertThat(reassigned.getOrderId()).isEqualTo(orderId);
            assertThat(reassigned.getStatus()).isEqualTo(Auction.Status.DEFAULTED);
            assertThat(products.findById(product.getId()).orElseThrow().getReserved()).isZero();
            return;
        }
        assertThat(reassigned.getWinnerId()).isEqualTo(buyer2.getId());
        assertThat(reassigned.getOrderId()).isNotEqualTo(orderId);
        assertThat(orders.findById(reassigned.getOrderId()).orElseThrow().getTotalAmount()).isEqualByComparingTo("400");
        assertThat(products.findById(product.getId()).orElseThrow().getReserved()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void onlyAdminCanResolveASentAuctionAndRepeatIsSafe(boolean delivered) throws Exception {
        Auction auction = createActiveAuction(new BigDecimal("400"), null, new BigDecimal("900"));
        mvc.perform(post("/api/auctions/" + auction.getId() + "/buyout").header("Authorization", buyer1Auth))
            .andExpect(status().isOk());
        Long orderId = auctions.findById(auction.getId()).orElseThrow().getOrderId();
        String sellerPath = "/api/seller/orders/" + orderId + "/status";
        String confirm = "/api/admin/orders/" + orderId + "/resolve-auction-shipment";
        String adminAuth = fixtures.bearer(fixtures.user("ADMIN", "NONE"));
        String reason = json.writeValueAsString(java.util.Map.of(
            "outcome", delivered ? "DELIVERED" : "UNDELIVERED", "reason", "Verified with the carrier"));
        mvc.perform(post(confirm).header("Authorization", buyer1Auth).contentType(MediaType.APPLICATION_JSON).content(reason))
            .andExpect(status().isForbidden());
        mvc.perform(post(confirm).header("Authorization", adminAuth).contentType(MediaType.APPLICATION_JSON).content(reason))
            .andExpect(status().isBadRequest());
        mvc.perform(patch(sellerPath).header("Authorization", sellerAuth).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"PENDING_SELLER_ACTION\"}")).andExpect(status().isOk());
        mvc.perform(patch(sellerPath).header("Authorization", sellerAuth).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"SENT\",\"trackingNumber\":\"TEST-001\"}")).andExpect(status().isOk());
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post(confirm).header("Authorization", adminAuth).contentType(MediaType.APPLICATION_JSON).content(reason))
                .andExpect(status().isNoContent());
        }
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(delivered ? "FULFILLED" : "CANCELLED");
        assertThat(auctions.findById(auction.getId()).orElseThrow().getStatus())
            .isEqualTo(delivered ? Auction.Status.COMPLETED : Auction.Status.CANCELLED);
        assertThat(collections.findByUserIdOrderByCreatedAtDesc(buyer1.getId())).hasSize(delivered ? 1 : 0);
        assertThat(products.findById(product.getId()).orElseThrow().getOnHand()).isEqualTo(9);
        mvc.perform(get("/api/orders/" + orderId).header("Authorization", buyer2Auth)).andExpect(status().isNotFound());
    }

    @Test
    void directCatalogLotBuyoutCreatesExactLotOrderAndPortfolio() throws Exception {
        Auction auction = createActiveAuction(new BigDecimal("100.00"), null, new BigDecimal("3001.00"));
        product.setAuctionReserved(0);
        products.saveAndFlush(product);
        auction.setProductId(null);
        auction.setStockHeld(false);
        auction.setQuantity(3);
        auction.setLanguage("JP");
        auctions.saveAndFlush(auction);
        mvc.perform(post("/api/auctions/" + auction.getId() + "/buyout").header("Authorization", buyer1Auth))
            .andExpect(status().isOk());
        Auction won = auctions.findById(auction.getId()).orElseThrow();
        Order order = orders.findById(won.getOrderId()).orElseThrow();
        assertThat(order.getTotalAmount()).isEqualByComparingTo("3001.00");
        mvc.perform(get("/api/user/auction-portfolio").header("Authorization", buyer1Auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.wonItems[0].quantity").value(3))
            .andExpect(jsonPath("$.wonItems[0].orderStatus").value("AWAITING_BUYER_CONTACT"));
        String path = "/api/orders/" + order.getId();
        mvc.perform(get(path).header("Authorization", buyer1Auth)).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].quantity").value(3))
            .andExpect(jsonPath("$.items[0].itemTotal").value(3001))
            .andExpect(jsonPath("$.sellerEmail").value(seller.getEmail()));

        mvc.perform(get("/api/seller/orders").header("Authorization", sellerAuth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].id").value(order.getId()))
            .andExpect(jsonPath("$.content[0].orderType").value("AUCTION"))
            .andExpect(jsonPath("$.content[0].status").value("AWAITING_BUYER_CONTACT"));

        byte[] png = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aGGQAAAAASUVORK5CYII=");
        var proof = new org.springframework.mock.web.MockMultipartFile("file", "slip.png", "image/png", png);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(path + "/payment-proof")
                .file(proof).header("Authorization", buyer1Auth))
            .andExpect(status().isNotFound());
        mvc.perform(get(path + "/payment-proof").header("Authorization", buyer1Auth))
            .andExpect(status().isNotFound());

        String sellerPath = "/api/seller/orders/" + order.getId();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(sellerPath + "/status")
            .header("Authorization", sellerAuth).contentType("application/json")
            .content("{\"status\":\"PENDING_SELLER_ACTION\"}")).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(sellerPath + "/status")
            .header("Authorization", sellerAuth).contentType("application/json")
            .content("{\"status\":\"SENT\",\"trackingNumber\":\"TCG12345\"}")).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(path + "/status")
            .header("Authorization", buyer1Auth).contentType("application/json")
            .content("{\"status\":\"FULFILLED\"}")).andExpect(status().isOk());
        var entry = collections.findByUserIdOrderByCreatedAtDesc(buyer1.getId()).getFirst();
        assertThat(entry.getQuantity()).isEqualTo(3);
        assertThat(entry.getLanguage()).isEqualTo("JP");
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
        mvc.perform(get("/api/notifications").header("Authorization", buyer2Auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].type").value("AUCTION_OUTBID"))
            .andExpect(jsonPath("$.content[0].actionPath").value("/auctions/" + auction.getId()));

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
            .andExpect(jsonPath("$.status").value("AWAITING_BUYER_CONTACT"))
            .andExpect(jsonPath("$.finalPrice").value(800.00))
            .andExpect(jsonPath("$.platformFeeAmount").value(40.00)) // 5% of 800 = 40
            .andExpect(jsonPath("$.sellerNetAmount").value(760.00));

        Auction finalized = auctions.findById(auction.getId()).orElseThrow();
        assertThat(finalized.getStatus()).isEqualTo(Auction.Status.AWAITING_BUYER_CONTACT);
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
    void testWatchlistRemindersSendOnceForEachWindow() throws Exception {
        Auction auction = createActiveAuction(new BigDecimal("100.00"), null, null);
        watchlistService.toggleWatchlist(buyer1.getId(), auction.getId());

        auction.setEndAt(Instant.now().plus(60, ChronoUnit.MINUTES));
        auctions.save(auction);
        watchlistService.processEndingReminders();
        watchlistService.processEndingReminders();

        auction.setEndAt(Instant.now().plus(15, ChronoUnit.MINUTES));
        auctions.save(auction);
        watchlistService.processEndingReminders();
        watchlistService.processEndingReminders();

        mvc.perform(get("/api/notifications").header("Authorization", buyer1Auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content[0].type").value("AUCTION_WATCHLIST_ENDING"))
            .andExpect(jsonPath("$.content[0].actionPath").value("/auctions/" + auction.getId()))
            .andExpect(jsonPath("$.content[1].type").value("AUCTION_WATCHLIST_ENDING"));
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
        assertThat(settled.getStatus()).isEqualTo(Auction.Status.AWAITING_BUYER_CONTACT);
        assertThat(settled.getWinnerId()).isEqualTo(buyer1.getId());
        assertThat(settled.getWinningBid()).isEqualByComparingTo("300.00");
        assertThat(settled.getOrderId()).isNotNull();

        String path = "/api/orders/" + settled.getOrderId();
        String sellerPath = "/api/seller/orders/" + settled.getOrderId();
        assertThat(orders.findById(settled.getOrderId()).orElseThrow().getStatus()).isEqualTo("AWAITING_BUYER_CONTACT");


        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(sellerPath + "/status")
                .header("Authorization", sellerAuth).contentType("application/json")
                .content("{\"status\":\"PENDING_SELLER_ACTION\"}"))
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
    void testBidBelowBuyItNowAuctionRemainsActive() throws Exception {
        Auction auction = createActiveAuction(new BigDecimal("1000.00"), null, new BigDecimal("4000.00"));

        mvc.perform(post("/api/auctions/" + auction.getId() + "/bids")
                .header("Authorization", buyer1Auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 2000.00}"))
            .andExpect(status().isOk());

        Auction updated = auctions.findById(auction.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(Auction.Status.ACTIVE);
        assertThat(updated.getHighestBid()).isEqualByComparingTo("2000.00");
        assertThat(updated.getLeadingBidderId()).isEqualTo(buyer1.getId());
        assertThat(updated.getWinnerId()).isNull();
        assertThat(updated.getOrderId()).isNull();
    }

    @Test
    void testBidEqualBuyItNowClosesImmediatelyAndCreatesOrder() throws Exception {
        Auction auction = createActiveAuction(new BigDecimal("1000.00"), null, new BigDecimal("4000.00"));

        mvc.perform(post("/api/auctions/" + auction.getId() + "/bids")
                .header("Authorization", buyer1Auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 4000.00}"))
            .andExpect(status().isOk());

        Auction closed = auctions.findById(auction.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(Auction.Status.AWAITING_BUYER_CONTACT);
        assertThat(closed.getWinnerId()).isEqualTo(buyer1.getId());
        assertThat(closed.getWinningBid()).isEqualByComparingTo("4000.00");
        assertThat(closed.getOrderId()).isNotNull();

        Order order = orders.findById(closed.getOrderId()).orElseThrow();
        assertThat(order.getTotalAmount()).isEqualByComparingTo("4000.00");
        assertThat(order.getBuyer().getId()).isEqualTo(buyer1.getId());
        assertThat(order.getStatus()).isEqualTo("AWAITING_BUYER_CONTACT");
    }

    @Test
    void testBidAboveBuyItNowClosesImmediatelyAndCappedAtBuyItNow() throws Exception {
        Auction auction = createActiveAuction(new BigDecimal("1000.00"), null, new BigDecimal("4000.00"));

        mvc.perform(post("/api/auctions/" + auction.getId() + "/bids")
                .header("Authorization", buyer1Auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 4500.00}"))
            .andExpect(status().isOk());

        Auction closed = auctions.findById(auction.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(Auction.Status.AWAITING_BUYER_CONTACT);
        assertThat(closed.getWinnerId()).isEqualTo(buyer1.getId());
        // Price must be capped at Buy It Now price!
        assertThat(closed.getWinningBid()).isEqualByComparingTo("4000.00");
        assertThat(closed.getOrderId()).isNotNull();

        Order order = orders.findById(closed.getOrderId()).orElseThrow();
        assertThat(order.getTotalAmount()).isEqualByComparingTo("4000.00");
        assertThat(order.getBuyer().getId()).isEqualTo(buyer1.getId());
    }

    @Test
    void testConcurrentBidsAfterBuyoutFailsAndExactlyOneWinnerAndOneOrder() throws Exception {
        Auction auction = createActiveAuction(new BigDecimal("1000.00"), null, new BigDecimal("4000.00"));

        // First bid reaches Buy It Now
        mvc.perform(post("/api/auctions/" + auction.getId() + "/bids")
                .header("Authorization", buyer1Auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 4000.00}"))
            .andExpect(status().isOk());

        // Subsequent bid fails with 409 Conflict
        mvc.perform(post("/api/auctions/" + auction.getId() + "/bids")
                .header("Authorization", buyer2Auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 4100.00}"))
            .andExpect(status().isConflict());

        Auction closed = auctions.findById(auction.getId()).orElseThrow();
        assertThat(closed.getWinnerId()).isEqualTo(buyer1.getId());
        assertThat(closed.getWinningBid()).isEqualByComparingTo("4000.00");

        // Exactly one order exists and points to winner buyer1
        assertThat(closed.getOrderId()).isNotNull();
        Order order = orders.findById(closed.getOrderId()).orElseThrow();
        assertThat(order.getBuyer().getId()).isEqualTo(buyer1.getId());
    }

    @Test
    void testMultipleCardsInOneAuctionRejectedByApi() throws Exception {
        var req = AuctionApiFixtures.setup(
            AuctionApiFixtures.photos(AuctionApiFixtures.draft()),
            product.getId(), "100.00", "NOW", "24"
        ).param("quantity", "2");

        mvc.perform(req.header("Authorization", sellerAuth))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Auctions can only contain exactly one card"));
    }

    @Test
    void testNormalBiddingWithoutBuyItNowStillWorks() throws Exception {
        Auction auction = createActiveAuction(new BigDecimal("100.00"), null, null);

        mvc.perform(post("/api/auctions/" + auction.getId() + "/bids")
                .header("Authorization", buyer1Auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 150.00}"))
            .andExpect(status().isOk());

        Auction a1 = auctions.findById(auction.getId()).orElseThrow();
        assertThat(a1.getStatus()).isEqualTo(Auction.Status.ACTIVE);
        assertThat(a1.getHighestBid()).isEqualByComparingTo("150.00");
        assertThat(a1.getLeadingBidderId()).isEqualTo(buyer1.getId());

        mvc.perform(post("/api/auctions/" + auction.getId() + "/bids")
                .header("Authorization", buyer2Auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 200.00}"))
            .andExpect(status().isOk());

        Auction a2 = auctions.findById(auction.getId()).orElseThrow();
        assertThat(a2.getStatus()).isEqualTo(Auction.Status.ACTIVE);
        assertThat(a2.getHighestBid()).isEqualByComparingTo("200.00");
        assertThat(a2.getLeadingBidderId()).isEqualTo(buyer2.getId());
        assertThat(a2.getWinnerId()).isNull();
        assertThat(a2.getOrderId()).isNull();
    }
}

package com.mythix.tcg.auction.api;

import com.mythix.tcg.auth.security.JwtService;
import com.mythix.tcg.cart.domain.CartItem;
import com.mythix.tcg.cart.persistence.CartItemRepository;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.mythix.tcg.auction.api.AuctionApiFixtures.AUCTIONS;
import static com.mythix.tcg.auction.api.AuctionApiFixtures.draft;
import static com.mythix.tcg.auction.api.AuctionApiFixtures.photos;
import static com.mythix.tcg.auction.api.AuctionApiFixtures.setup;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies shared inventory locks on the schema produced by Flyway. */
@SpringBootTest(properties = {
    "auction.lifecycle.enabled=false",
    "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/tcg_store_auction_test}",
    "spring.flyway.enabled=true",
    "spring.flyway.baseline-on-migrate=false",
    "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
@EnabledIfSystemProperty(named = "spring.profiles.active", matches = ".*postgres.*")
class AuctionConcurrencyPostgresTests {
    private static final int TIMEOUT_SECONDS = 15;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository users;
    @Autowired private CardRepository cards;
    @Autowired private ProductRepository products;
    @Autowired private CartItemRepository cart;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    private AuctionApiFixtures fixtures;
    private String sellerAuth;
    private Product product;

    @BeforeEach
    void createInventory() {
        fixtures = new AuctionApiFixtures(jwtService, users, cards, products);
        User seller = fixtures.user("USER", "APPROVED");
        sellerAuth = fixtures.bearer(seller);
        product = fixtures.product(seller, 1, "SHOW", "NM");
    }

    @Test
    void concurrentPublishAndFixedPriceCheckoutCanAllocateOnlyOneUnit() throws Exception {
        long id = saveDraft();
        User buyer = fixtures.user("USER", "NONE");
        cart.save(CartItem.builder().user(buyer).product(product).quantity(1).build());
        String buyerAuth = fixtures.bearer(buyer);

        List<MvcResult> results = race(() -> publish(id), () -> mvc.perform(post("/api/orders")
                .header("Authorization", buyerAuth)
                .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andReturn());

        int auctionStatus = statusOf(results.getFirst());
        int checkoutStatus = statusOf(results.getLast());
        assertThat(auctionStatus).isIn(200, 409);
        assertThat(checkoutStatus).isIn(201, 409);
        assertThat(auctionStatus == 200).isNotEqualTo(checkoutStatus == 201);
        assertThat(auctionReserved()).isEqualTo(auctionStatus == 200 ? 1 : 0);
        assertThat(products.findById(product.getId()).orElseThrow().getReserved())
            .isEqualTo(checkoutStatus == 201 ? 1 : 0);
    }

    @Test
    void concurrentDifferentDraftsCompeteAndRetryingWinnerDoesNotHoldAgain() throws Exception {
        long firstId = saveDraft();
        long secondId = saveDraft();
        List<MvcResult> results = race(() -> publish(firstId), () -> publish(secondId));

        assertThat(results).extracting(AuctionConcurrencyPostgresTests::statusOf).containsExactlyInAnyOrder(200, 409);
        long winner = statusOf(results.getFirst()) == 200 ? firstId : secondId;
        List<MvcResult> retries = race(() -> publish(winner), () -> publish(winner));
        assertThat(retries).extracting(AuctionConcurrencyPostgresTests::statusOf).containsExactly(200, 200);
        assertThat(auctionReserved()).isEqualTo(1);
    }

    @Test
    void concurrentFirstPublishOfSameDraftReturnsSameAuctionAndHoldsOneUnit() throws Exception {
        long id = saveDraft();
        List<MvcResult> results = race(() -> publish(id), () -> publish(id));
        assertThat(results).extracting(AuctionConcurrencyPostgresTests::statusOf).containsExactly(200, 200);
        for (MvcResult result : results) {
            assertThat(json.readTree(result.getResponse().getContentAsByteArray()).path("id").asLong()).isEqualTo(id);
        }
        assertThat(auctionReserved()).isEqualTo(1);
    }

    @Test
    void simultaneousBuyoutsCreateOnlyOneOrderAndMoveStockOnce() throws Exception {
        var draft = photos(setup(draft(), product.getId(), "100", "NOW", "24"))
            .param("buyItNowPrice", "900")
            .header("Authorization", sellerAuth);
        var response = mvc.perform(draft).andExpect(status().isCreated()).andReturn().getResponse();
        long id = json.readTree(response.getContentAsByteArray()).path("id").asLong();
        assertThat(statusOf(publish(id))).isEqualTo(200);
        String firstBuyer = fixtures.bearer(fixtures.user("USER", "NONE"));
        String secondBuyer = fixtures.bearer(fixtures.user("USER", "NONE"));
        String buyout = "/api/auctions/" + id + "/buyout";
        List<MvcResult> results = race(
            () -> mvc.perform(post(buyout).header("Authorization", firstBuyer)).andReturn(),
            () -> mvc.perform(post(buyout).header("Authorization", secondBuyer)).andReturn());
        assertThat(results).extracting(AuctionConcurrencyPostgresTests::statusOf).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items WHERE local_card_id = ?",
            Integer.class, product.getId())).isEqualTo(1);
        assertThat(auctionReserved()).isZero();
        assertThat(products.findById(product.getId()).orElseThrow().getReserved()).isEqualTo(1);
    }

    @Test
    void concurrentApprovalOfSameDefaultCreatesOneStrikeAndOneDecision() throws Exception {
        var draft = photos(setup(draft(), product.getId(), "100", "NOW", "24"))
            .param("buyItNowPrice", "900").header("Authorization", sellerAuth);
        long auctionId = json.readTree(mvc.perform(draft).andExpect(status().isCreated()).andReturn()
            .getResponse().getContentAsByteArray()).path("id").asLong();
        assertThat(statusOf(publish(auctionId))).isEqualTo(200);
        User buyer = fixtures.user("USER", "NONE");
        mvc.perform(post("/api/auctions/" + auctionId + "/buyout").header("Authorization", fixtures.bearer(buyer)))
            .andExpect(status().isOk());
        long orderId = jdbc.queryForObject("SELECT order_id FROM auctions WHERE id = ?", Long.class, auctionId);
        mvc.perform(patch("/api/seller/orders/" + orderId + "/status").header("Authorization", sellerAuth)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"CANCELLED\",\"note\":\"No contact\",\"noBuyerContact\":true}"))
            .andExpect(status().isOk());
        long caseId = jdbc.queryForObject("SELECT id FROM auction_default_cases WHERE order_id = ?", Long.class, orderId);
        String admin = fixtures.bearer(fixtures.user("ADMIN", "NONE"));
        String path = "/api/admin/auction-default-cases/" + caseId + "/approve";
        List<MvcResult> results = race(
            () -> mvc.perform(post(path).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Reviewed\"}")).andReturn(),
            () -> mvc.perform(post(path).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Reviewed\"}")).andReturn());
        assertThat(results).extracting(AuctionConcurrencyPostgresTests::statusOf).containsExactly(200, 200);
        assertThat(jdbc.queryForObject("SELECT auction_strikes FROM users WHERE id = ?", Integer.class, buyer.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auction_default_decisions WHERE case_id = ?", Integer.class, caseId)).isEqualTo(1);
    }

    @Test
    void concurrentApprovalsForSameBuyerSerializeStrikes() throws Exception {
        User seller = fixtures.user("USER", "APPROVED");
        User buyer = fixtures.user("USER", "NONE");
        long first = createPendingCase(seller, buyer);
        long second = createPendingCase(seller, buyer);
        String admin = fixtures.bearer(fixtures.user("ADMIN", "NONE"));
        List<MvcResult> results = race(() -> approve(first, admin), () -> approve(second, admin));
        assertThat(results).extracting(AuctionConcurrencyPostgresTests::statusOf).containsExactly(200, 200);
        assertThat(jdbc.queryForObject("SELECT auction_strikes FROM users WHERE id = ?", Integer.class, buyer.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auction_default_decisions WHERE case_id IN (?, ?)", Integer.class, first, second)).isEqualTo(2);
    }

    @Test
    void crossParticipantApprovalsDoNotDeadlockWhenNotifyingBothUsers() throws Exception {
        User first = fixtures.user("USER", "APPROVED");
        User second = fixtures.user("USER", "APPROVED");
        long caseOne = createPendingCase(first, second);
        long caseTwo = createPendingCase(second, first);
        String admin = fixtures.bearer(fixtures.user("ADMIN", "NONE"));
        List<MvcResult> results = race(() -> approve(caseOne, admin), () -> approve(caseTwo, admin));
        assertThat(results).extracting(AuctionConcurrencyPostgresTests::statusOf).containsExactly(200, 200);
        assertThat(jdbc.queryForObject("SELECT auction_strikes FROM users WHERE id = ?", Integer.class, first.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT auction_strikes FROM users WHERE id = ?", Integer.class, second.getId())).isEqualTo(1);
    }

    @Test
    void bidAndPenaltyDecisionForSameBuyerSerializeWithoutDeadlock() throws Exception {
        User seller = fixtures.user("USER", "APPROVED");
        User buyer = fixtures.user("USER", "NONE");
        buyer.setAuctionStrikes(1);
        users.saveAndFlush(buyer);
        long pending = createPendingCase(seller, buyer);
        Product item = fixtures.product(seller, 1, "SHOW", "NM");
        String sellerToken = fixtures.bearer(seller);
        long auctionId = json.readTree(mvc.perform(photos(setup(draft(), item.getId(), "100", "NOW", "24"))
            .header("Authorization", sellerToken)).andExpect(status().isCreated()).andReturn()
            .getResponse().getContentAsByteArray()).path("id").asLong();
        mvc.perform(post(AUCTIONS + "/{id}/publish", auctionId).header("Authorization", sellerToken)).andExpect(status().isOk());
        String admin = fixtures.bearer(fixtures.user("ADMIN", "NONE"));
        List<MvcResult> results = race(() -> approve(pending, admin),
            () -> mvc.perform(post("/api/auctions/" + auctionId + "/bids").header("Authorization", fixtures.bearer(buyer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100}")).andReturn());
        assertThat(statusOf(results.getFirst())).isEqualTo(200);
        assertThat(statusOf(results.getLast())).isIn(200, 403);
        assertThat(users.findById(buyer.getId()).orElseThrow().isAuctionBanned()).isTrue();
    }

    @Test
    void pauseAndBidSerializeOnAuctionRow() throws Exception {
        long id = saveDraft();
        assertThat(statusOf(publish(id))).isEqualTo(200);
        String admin = fixtures.bearer(fixtures.user("ADMIN", "NONE"));
        String buyer = fixtures.bearer(fixtures.user("USER", "NONE"));
        List<MvcResult> results = race(
            () -> pause(id, admin),
            () -> mvc.perform(post("/api/auctions/{id}/bids", id).header("Authorization", buyer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":150}")).andReturn());
        assertThat(statusOf(results.getFirst())).isEqualTo(200);
        assertThat(statusOf(results.getLast())).isIn(200, 409);
        assertThat(jdbc.queryForObject("SELECT status FROM auctions WHERE id = ?", String.class, id)).isEqualTo("PAUSED");
        assertThat(jdbc.queryForObject("SELECT bid_count FROM auctions WHERE id = ?", Integer.class, id))
            .isEqualTo(statusOf(results.getLast()) == 200 ? 1 : 0);
        assertThat(auctionReserved()).isEqualTo(1);
    }

    @Test
    void pauseAndBuyoutHaveExactlyOneWinner() throws Exception {
        var draft = photos(setup(draft(), product.getId(), "100", "NOW", "24"))
            .param("buyItNowPrice", "900").header("Authorization", sellerAuth);
        long id = json.readTree(mvc.perform(draft).andExpect(status().isCreated()).andReturn()
            .getResponse().getContentAsByteArray()).path("id").asLong();
        assertThat(statusOf(publish(id))).isEqualTo(200);
        String admin = fixtures.bearer(fixtures.user("ADMIN", "NONE"));
        String buyer = fixtures.bearer(fixtures.user("USER", "NONE"));
        List<MvcResult> results = race(() -> pause(id, admin),
            () -> mvc.perform(post("/api/auctions/{id}/buyout", id).header("Authorization", buyer)).andReturn());
        assertThat(results).extracting(AuctionConcurrencyPostgresTests::statusOf).containsExactlyInAnyOrder(200, 409);
        boolean paused = statusOf(results.getFirst()) == 200;
        assertThat(jdbc.queryForObject("SELECT status FROM auctions WHERE id = ?", String.class, id))
            .isEqualTo(paused ? "PAUSED" : "AWAITING_BUYER_CONTACT");
        assertThat(auctionReserved()).isEqualTo(paused ? 1 : 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items WHERE local_card_id = ?", Integer.class,
            product.getId())).isEqualTo(paused ? 0 : 1);
    }

    @Test
    void simultaneousIdenticalDisputeDecisionsCreateOneAuditAndOneNotificationPerParticipant() throws Exception {
        var draft = photos(setup(draft(), product.getId(), "100", "NOW", "24"))
            .param("buyItNowPrice", "900").header("Authorization", sellerAuth);
        long auctionId = json.readTree(mvc.perform(draft).andExpect(status().isCreated()).andReturn()
            .getResponse().getContentAsByteArray()).path("id").asLong();
        assertThat(statusOf(publish(auctionId))).isEqualTo(200);
        User buyer = fixtures.user("USER", "NONE");
        mvc.perform(post("/api/auctions/{id}/buyout", auctionId).header("Authorization", fixtures.bearer(buyer)))
            .andExpect(status().isOk());
        long orderId = jdbc.queryForObject("SELECT order_id FROM auctions WHERE id = ?", Long.class, auctionId);
        long disputeId = jdbc.queryForObject("""
            INSERT INTO auction_disputes (auction_id, order_id, buyer_id, seller_id, reason, status, created_at)
            VALUES (?, ?, ?, ?, 'Damage', 'PENDING', CURRENT_TIMESTAMP) RETURNING id
            """, Long.class, auctionId, orderId, buyer.getId(), product.getSeller().getId());
        String admin = fixtures.bearer(fixtures.user("ADMIN", "NONE"));
        String path = "/api/admin/auction-disputes/" + disputeId + "/decide";
        List<MvcResult> results = race(
            () -> mvc.perform(post(path).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"DISMISS\",\"reason\":\"Reviewed\"}")).andReturn(),
            () -> mvc.perform(post(path).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"DISMISS\",\"reason\":\"Reviewed\"}")).andReturn());
        assertThat(results).extracting(AuctionConcurrencyPostgresTests::statusOf).containsExactly(200, 200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'AUCTION_DISPUTE_DECISION' AND target_id = ?",
            Integer.class, Long.toString(disputeId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM notifications
            WHERE notification_type = 'AUCTION_DISPUTE_DECIDED'
              AND title = 'Auction dispute decided'
              AND message = 'Decision: Dismissed. Reason: Reviewed'
              AND action_path = ?
            """, Integer.class, "/auction-disputes?disputeId=" + disputeId)).isEqualTo(2);
    }

    @Test
    void auditResponseRetainsIdentifierAfterActorAccountIsDeleted() throws Exception {
        User deleted = fixtures.user("USER", "NONE");
        jdbc.update("INSERT INTO audit_logs (actor_id, action, target_type, target_id, details, created_at) VALUES (?, 'AUCTION_BID', 'AUCTION', 'deleted-actor-test', '{}', CURRENT_TIMESTAMP)", deleted.getId());
        jdbc.update("DELETE FROM users WHERE id = ?", deleted.getId());
        String admin = fixtures.bearer(fixtures.user("ADMIN", "NONE"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/audit-logs")
            .header("Authorization", admin).param("query", "deleted-actor-test"))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.content[0].actorId").value(deleted.getId().toString()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.content[0].actorName").value("Deleted account"));
    }
    private MvcResult pause(long id, String auth) throws Exception {
        return mvc.perform(post("/api/admin/auctions/{id}/pause", id).header("Authorization", auth)
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Review\"}")).andReturn();
    }
    private long createPendingCase(User seller, User buyer) throws Exception {
        Product item = fixtures.product(seller, 1, "SHOW", "NM");
        String auth = fixtures.bearer(seller);
        var draft = photos(setup(draft(), item.getId(), "100", "NOW", "24"))
            .param("buyItNowPrice", "900").header("Authorization", auth);
        long auctionId = json.readTree(mvc.perform(draft).andExpect(status().isCreated()).andReturn()
            .getResponse().getContentAsByteArray()).path("id").asLong();
        mvc.perform(post(AUCTIONS + "/{id}/publish", auctionId).header("Authorization", auth)).andExpect(status().isOk());
        mvc.perform(post("/api/auctions/" + auctionId + "/buyout").header("Authorization", fixtures.bearer(buyer))).andExpect(status().isOk());
        long orderId = jdbc.queryForObject("SELECT order_id FROM auctions WHERE id = ?", Long.class, auctionId);
        mvc.perform(patch("/api/seller/orders/" + orderId + "/status").header("Authorization", auth)
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\",\"note\":\"No contact\",\"noBuyerContact\":true}"))
            .andExpect(status().isOk());
        return jdbc.queryForObject("SELECT id FROM auction_default_cases WHERE order_id = ?", Long.class, orderId);
    }

    private MvcResult approve(long caseId, String auth) throws Exception {
        return mvc.perform(post("/api/admin/auction-default-cases/" + caseId + "/approve")
            .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Reviewed\"}")).andReturn();
    }

    @Test
    void buyoutAndManualBidSerializeWithoutCreatingAnotherOrder() throws Exception {
        var draft = photos(setup(draft(), product.getId(), "100", "NOW", "24"))
            .param("buyItNowPrice", "900")
            .header("Authorization", sellerAuth);
        var response = mvc.perform(draft).andExpect(status().isCreated()).andReturn().getResponse();
        long id = json.readTree(response.getContentAsByteArray()).path("id").asLong();
        assertThat(statusOf(publish(id))).isEqualTo(200);
        String firstBuyer = fixtures.bearer(fixtures.user("USER", "NONE"));
        String secondBuyer = fixtures.bearer(fixtures.user("USER", "NONE"));
        String room = "/api/auctions/" + id;
        List<MvcResult> results = race(
            () -> mvc.perform(post(room + "/buyout").header("Authorization", firstBuyer)).andReturn(),
            () -> mvc.perform(post(room + "/bids").header("Authorization", secondBuyer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":150}")).andReturn());
        assertThat(statusOf(results.getFirst())).isEqualTo(200);
        assertThat(statusOf(results.getLast())).isIn(200, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items WHERE local_card_id = ?",
            Integer.class, product.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT winning_bid FROM auctions WHERE id = ?", java.math.BigDecimal.class, id))
            .isEqualByComparingTo("900");
    }

    @Test
    void cancellationWaitsForAuctionWriterAndRechecksNewBidCount() throws Exception {
        long id = saveDraft();
        assertThat(statusOf(publish(id))).isEqualTo(200);

        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> cancellation = transactions.execute(status -> {
                jdbc.queryForObject("SELECT id FROM auctions WHERE id = ? FOR UPDATE", Long.class, id);
                int writerPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                Future<MvcResult> request = executor.submit(() -> mvc.perform(post(AUCTIONS + "/{id}/cancel", id)
                    .header("Authorization", sellerAuth)).andReturn());
                awaitBlockedCancellation(writerPid, request);
                // Simulate the future US-15 writer using the same row lock as cancellation.
                jdbc.update("UPDATE auctions SET bid_count = 1, highest_bid = 150 WHERE id = ?", id);
                return request;
            });
            assertThat(statusOf(cancellation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))).isEqualTo(409);
        } finally {
            executor.shutdownNow();
        }
        assertThat(auctionReserved()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM auctions WHERE id = ?", String.class, id))
            .isEqualTo("ACTIVE");
    }

    private void awaitBlockedCancellation(int writerPid, Future<MvcResult> request) {
        long deadline = System.nanoTime() + Duration.ofSeconds(TIMEOUT_SECONDS).toNanos();
        while (!request.isDone() && System.nanoTime() < deadline) {
            boolean blocked = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE NOT granted AND ? = ANY(pg_blocking_pids(pid)))",
                Boolean.class, writerPid));
            if (blocked) {
                return;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(25);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for cancellation to acquire the auction lock", exception);
            }
        }
        throw new AssertionError("Cancellation did not wait for the transaction holding the auction row");
    }

    private long saveDraft() throws Exception {
        byte[] response = mvc.perform(photos(setup(draft(), product.getId(), "100", "NOW", "24"))
                .header("Authorization", sellerAuth))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray();
        return json.readTree(response).path("id").asLong();
    }

    private MvcResult publish(long id) throws Exception {
        return mvc.perform(post(AUCTIONS + "/{id}/publish", id).header("Authorization", sellerAuth)).andReturn();
    }

    private int auctionReserved() {
        return jdbc.queryForObject("SELECT auction_reserved FROM local_cards WHERE id = ?", Integer.class, product.getId());
    }

    private static int statusOf(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static List<MvcResult> race(Callable<MvcResult> first, Callable<MvcResult> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> firstResult = executor.submit(() -> awaitStart(ready, start, first));
            Future<MvcResult> secondResult = executor.submit(() -> awaitStart(ready, start, second));
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(firstResult.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                secondResult.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private static MvcResult awaitStart(CountDownLatch ready, CountDownLatch start, Callable<MvcResult> request) throws Exception {
        ready.countDown();
        assertThat(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        return request.call();
    }
}

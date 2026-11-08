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

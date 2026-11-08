package com.mythix.tcg.auction.api;

import com.mythix.tcg.auction.application.AuctionService;
import com.mythix.tcg.auth.security.JwtService;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.collection.domain.CollectionItem;
import com.mythix.tcg.collection.persistence.CollectionRepository;
import com.mythix.tcg.notification.domain.NotificationTypes;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static com.mythix.tcg.auction.api.AuctionApiFixtures.ADMIN_REQUESTS;
import static com.mythix.tcg.auction.api.AuctionApiFixtures.AUCTIONS;
import static com.mythix.tcg.auction.api.AuctionApiFixtures.draft;
import static com.mythix.tcg.auction.api.AuctionApiFixtures.edit;
import static com.mythix.tcg.auction.api.AuctionApiFixtures.image;
import static com.mythix.tcg.auction.api.AuctionApiFixtures.photos;
import static com.mythix.tcg.auction.api.AuctionApiFixtures.setup;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "auction.lifecycle.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuctionControllerIntegrationTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository users;
    @Autowired private CardRepository cards;
    @Autowired private CollectionRepository collections;
    @Autowired private ProductRepository products;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuctionService auctionService;

    private AuctionApiFixtures fixtures;
    private User seller;
    private User otherSeller;
    private User customer;
    private User admin;
    private Product product;
    private String sellerAuth;
    private String otherSellerAuth;
    private String adminAuth;

    @BeforeEach
    void createInventory() {
        fixtures = new AuctionApiFixtures(jwtService, users, cards, products);
        seller = fixtures.user("USER", "APPROVED");
        otherSeller = fixtures.user("USER", "APPROVED");
        customer = fixtures.user("USER", "NONE");
        admin = fixtures.user("ADMIN", "NONE");
        product = fixtures.product(seller, 1, "SHOW");
        sellerAuth = fixtures.bearer(seller);
        otherSellerAuth = fixtures.bearer(otherSeller);
        adminAuth = fixtures.bearer(admin);
    }

    @Test
    void sellerAndAdminRoutesEnforceCurrentRoleAndResourceOwnership() throws Exception {
        mvc.perform(get(AUCTIONS)).andExpect(status().isUnauthorized());
        mvc.perform(get(AUCTIONS).header("Authorization", fixtures.bearer(customer))).andExpect(status().isForbidden());
        mvc.perform(get(ADMIN_REQUESTS).header("Authorization", sellerAuth)).andExpect(status().isForbidden());

        Product foreign = fixtures.product(otherSeller, 1, "SHOW");
        mvc.perform(get(AUCTIONS + "/inventory").header("Authorization", sellerAuth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].productId").value(product.getId()));
        mvc.perform(setup(draft(), foreign.getId(), "10", "NOW", "24").header("Authorization", sellerAuth))
            .andExpect(status().isNotFound());

        long id = saveDraft(completeDraft()).path("id").asLong();
        mvc.perform(get(AUCTIONS + "/{id}", id).header("Authorization", otherSellerAuth))
            .andExpect(status().isNotFound());
        mvc.perform(post(AUCTIONS + "/{id}/publish", id).header("Authorization", otherSellerAuth))
            .andExpect(status().isNotFound());

        seller.setSellerStatus("REJECTED");
        users.saveAndFlush(seller);
        mvc.perform(get(AUCTIONS).header("Authorization", sellerAuth)).andExpect(status().isForbidden());
        assertThat(heldStock()).isZero();
    }

    @Test
    void incompleteDraftCanBeSavedEditedAndDeletedWithoutHoldingInventory() throws Exception {
        JsonNode empty = saveDraft(draft());
        long id = empty.path("id").asLong();
        assertThat(empty.path("status").asText()).isEqualTo("DRAFT");
        assertThat(heldStock()).isZero();

        mvc.perform(photos(setup(edit(id), product.getId(), "500", "NOW", "24"))
                .header("Authorization", sellerAuth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.startPrice").value(500));
        assertThat(heldStock()).isZero();
        mvc.perform(delete(AUCTIONS + "/{id}/draft", id).header("Authorization", sellerAuth))
            .andExpect(status().isNoContent());
        mvc.perform(get(AUCTIONS + "/{id}", id).header("Authorization", sellerAuth))
            .andExpect(status().isNotFound());
    }

    @Test
    void retryingDraftCreationWithSameKeyReturnsSameDraft() throws Exception {
        String key = UUID.randomUUID().toString();
        JsonNode first = body(mvc.perform(completeDraft().header("Authorization", sellerAuth)
            .header("Idempotency-Key", key)).andExpect(status().isCreated()));
        JsonNode retry = body(mvc.perform(completeDraft().header("Authorization", sellerAuth)
            .header("Idempotency-Key", key)).andExpect(status().isCreated()));
        assertThat(retry.path("id").asLong()).isEqualTo(first.path("id").asLong());
        mvc.perform(get(AUCTIONS).header("Authorization", sellerAuth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        assertThat(heldStock()).isZero();
    }

    @Test
    void collectionOnlyMarksItemsThatAlreadyExistInSellerInventory() throws Exception {
        Product hidden = fixtures.product(seller, 1, "HIDE");
        Product unrelated = fixtures.product(otherSeller, 1, "SHOW");
        collections.save(CollectionItem.builder().user(seller).card(hidden.getCard())
            .condition(hidden.getCondition()).language(hidden.getLanguage()).build());
        collections.save(CollectionItem.builder().user(seller).card(unrelated.getCard())
            .condition(unrelated.getCondition()).language(unrelated.getLanguage()).build());

        mvc.perform(get(AUCTIONS + "/inventory").header("Authorization", sellerAuth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[?(@.collectionMatch == true)].productId", hasItem(hidden.getId().intValue())));

        long id = saveDraft(photos(setup(draft(), hidden.getId(), "100", "NOW", "24"))).path("id").asLong();
        publish(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void publishingTwiceHoldsOneUnitAndDirectCancellationReleasesIt() throws Exception {
        long id = saveDraft(completeDraft()).path("id").asLong();
        JsonNode first = body(publish(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE")));
        JsonNode retry = body(publish(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE")));
        assertThat(retry.path("id").asLong()).isEqualTo(id);
        assertThat(retry.path("startAt").asText()).isEqualTo(first.path("startAt").asText());
        assertThat(Duration.between(instant(first, "startAt"), instant(first, "endAt"))).isEqualTo(Duration.ofHours(24));
        assertThat(instant(first, "startAt").getNano()).isZero();
        assertThat(instant(first, "endAt").getNano()).isZero();
        assertThat(heldStock()).isEqualTo(1);
        assertThat(products.findById(product.getId()).orElseThrow().getOnHand()).isEqualTo(1);
        mvc.perform(delete("/api/seller/products/{id}", product.getId()).header("Authorization", sellerAuth))
            .andExpect(status().isConflict());

        long competingDraft = saveDraft(completeDraft()).path("id").asLong();
        publish(competingDraft).andExpect(status().isConflict());
        assertStatus(competingDraft, "DRAFT");
        assertThat(heldStock()).isEqualTo(1);
        mvc.perform(delete(AUCTIONS + "/{id}/draft", id).header("Authorization", sellerAuth))
            .andExpect(status().isConflict());
        mvc.perform(post(AUCTIONS + "/{id}/cancel", id).header("Authorization", sellerAuth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(heldStock()).isZero();
        publish(competingDraft).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @ParameterizedTest
    @CsvSource({"0,,", "100,99,", "100,,100", "100,150,150"})
    void invalidPriceRelationshipsCannotBeSaved(String start, String reserve, String buyItNow) throws Exception {
        MockMultipartHttpServletRequestBuilder request = photos(setup(draft(), product.getId(), start, "NOW", "24"));
        if (reserve != null) {
            request.param("reservePrice", reserve);
        }
        if (buyItNow != null) {
            request.param("buyItNowPrice", buyItNow);
        }
        mvc.perform(request.header("Authorization", sellerAuth)).andExpect(status().isBadRequest());
        assertThat(heldStock()).isZero();
    }

    @Test
    void equalReserveAndHigherBuyItNowAreAcceptedForSeller() throws Exception {
        MockMultipartHttpServletRequestBuilder request = completeDraft();
        request.param("reservePrice", "100").param("buyItNowPrice", "100.01");
        long id = saveDraft(request).path("id").asLong();
        publish(id).andExpect(status().isOk())
            .andExpect(jsonPath("$.reservePrice").value(100))
            .andExpect(jsonPath("$.buyItNowPrice").value(100.01));
        mvc.perform(get(AUCTIONS + "/{id}", id).header("Authorization", fixtures.bearer(customer)))
            .andExpect(status().isForbidden());
    }

    @Test
    void unsupportedAuctionDurationCannotBeSaved() throws Exception {
        mvc.perform(photos(setup(draft(), product.getId(), "100", "NOW", "48")).header("Authorization", sellerAuth))
            .andExpect(status().isBadRequest());
        assertThat(heldStock()).isZero();
    }

    @Test
    void publishRejectsMissingBackPhotoAndDraftRejectsInvalidImageContent() throws Exception {
        MockMultipartHttpServletRequestBuilder request = setup(draft(), product.getId(), "100", "NOW", "24");
        request.file(image("frontImage"));
        long id = saveDraft(request).path("id").asLong();
        publish(id).andExpect(status().isBadRequest());

        MockMultipartHttpServletRequestBuilder invalid = setup(edit(id), product.getId(), "100", "NOW", "24");
        invalid.file(new MockMultipartFile("backImage", "fake.png", "image/png", new byte[]{1, 2, 3}));
        mvc.perform(invalid.header("Authorization", sellerAuth)).andExpect(status().isBadRequest());
        assertThat(heldStock()).isZero();
    }

    @Test
    void publishRejectsSchedulesLessThanFifteenMinutesAway() throws Exception {
        MockMultipartHttpServletRequestBuilder request = photos(setup(draft(), product.getId(), "100", "SCHEDULED", "72"));
        request.param("startAt", Instant.now().plus(14, ChronoUnit.MINUTES).toString());
        long id = saveDraft(request).path("id").asLong();
        publish(id).andExpect(status().isBadRequest());
        assertThat(heldStock()).isZero();
    }

    @Test
    void scheduledAuctionActivatesAndReleasesUnsoldStockAtEnd() throws Exception {
        Instant start = Instant.now().plus(20, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        MockMultipartHttpServletRequestBuilder request = photos(setup(draft(), product.getId(), "100", "SCHEDULED", "72"));
        request.param("startAt", start.toString());
        long id = saveDraft(request).path("id").asLong();
        JsonNode scheduled = body(publish(id).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SCHEDULED")));
        Instant end = start.plus(3, ChronoUnit.DAYS);
        assertThat(instant(scheduled, "startAt")).isEqualTo(start);
        assertThat(instant(scheduled, "endAt")).isEqualTo(end);
        assertThat(heldStock()).isEqualTo(1);

        auctionService.advanceLifecycle(id, start.minusSeconds(1));
        assertStatus(id, "SCHEDULED");
        auctionService.advanceLifecycle(id, start);
        assertStatus(id, "ACTIVE");
        assertThat(heldStock()).isEqualTo(1);
        auctionService.advanceLifecycle(id, end);
        auctionService.advanceLifecycle(id, end.plusSeconds(1));
        assertStatus(id, "ENDED");
        assertThat(heldStock()).isZero();
    }

    @Test
    void bidPreventsSelfCancellationAndOnlyOwnerAndAdminCanReadEvidence() throws Exception {
        long id = activeAuctionWithBid();
        mvc.perform(post(AUCTIONS + "/{id}/cancel", id).header("Authorization", sellerAuth))
            .andExpect(status().isConflict());
        JsonNode request = submitEvidence(id);
        long requestId = request.path("id").asLong();
        assertThat(request.path("status").asText()).isEqualTo("PENDING_REVIEW");
        assertStatus(id, "ACTIVE");
        assertThat(heldStock()).isEqualTo(1);
        assertThat(notificationCount(admin, NotificationTypes.AUCTION_CANCELLATION_SUBMITTED)).isEqualTo(1L);

        JsonNode sellerView = body(mvc.perform(get(AUCTIONS + "/{id}", id).header("Authorization", sellerAuth))
            .andExpect(status().isOk()));
        String sellerEvidence = sellerView.path("cancellationRequest").path("evidence").get(0).path("url").asText();
        mvc.perform(get(sellerEvidence).header("Authorization", sellerAuth))
            .andExpect(status().isOk()).andExpect(content().bytes(image("evidence").getBytes()));
        mvc.perform(get(sellerEvidence).header("Authorization", otherSellerAuth)).andExpect(status().isNotFound());
        JsonNode adminView = body(mvc.perform(get(ADMIN_REQUESTS + "/{id}", requestId)
                .header("Authorization", adminAuth)).andExpect(status().isOk()));
        String adminEvidence = adminView.path("evidence").get(0).path("url").asText();
        mvc.perform(get(adminEvidence)).andExpect(status().isUnauthorized());
        mvc.perform(get(adminEvidence).header("Authorization", fixtures.bearer(customer))).andExpect(status().isForbidden());
        mvc.perform(get(adminEvidence).header("Authorization", adminAuth))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().bytes(image("evidence").getBytes()));

        mvc.perform(post(ADMIN_REQUESTS + "/{id}/approve", requestId).header("Authorization", adminAuth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(post(ADMIN_REQUESTS + "/{id}/approve", requestId).header("Authorization", adminAuth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM audit_logs WHERE target_type = 'AUCTION_CANCELLATION' AND target_id = ?
            """, Long.class, Long.toString(requestId))).isEqualTo(1L);
        assertThat(notificationCount(seller, NotificationTypes.AUCTION_CANCELLATION_DECIDED)).isEqualTo(1L);
        assertStatus(id, "CANCELLED");
        assertThat(heldStock()).isZero();
    }

    @Test
    void rejectionKeepsAuctionActiveAndStaleApprovalCannotCancelAnEndedAuction() throws Exception {
        long id = activeAuctionWithBid();
        long requestId = submitEvidence(id).path("id").asLong();
        mvc.perform(post(ADMIN_REQUESTS + "/{id}/reject", requestId).header("Authorization", adminAuth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Evidence is inconclusive\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        assertStatus(id, "ACTIVE");
        assertThat(heldStock()).isEqualTo(1);

        long staleId = submitEvidence(id).path("id").asLong();
        auctionService.advanceLifecycle(id, Instant.now().plus(25, ChronoUnit.HOURS));
        mvc.perform(post(ADMIN_REQUESTS + "/{id}/approve", staleId).header("Authorization", adminAuth))
            .andExpect(status().isConflict());
        assertStatus(id, "ENDED");
        assertThat(heldStock()).isEqualTo(1);
    }

    private MockMultipartHttpServletRequestBuilder completeDraft() {
        return photos(setup(draft(), product.getId(), "100", "NOW", "24"));
    }

    private JsonNode saveDraft(MockMultipartHttpServletRequestBuilder request) throws Exception {
        return body(mvc.perform(request.header("Authorization", sellerAuth)).andExpect(status().isCreated()));
    }

    private ResultActions publish(long auctionId) throws Exception {
        return mvc.perform(post(AUCTIONS + "/{id}/publish", auctionId).header("Authorization", sellerAuth));
    }

    private long activeAuctionWithBid() throws Exception {
        long id = saveDraft(completeDraft()).path("id").asLong();
        publish(id).andExpect(status().isOk());
        // Fixture for US-15 input. There is no buyer bid route in this release.
        jdbc.update("UPDATE auctions SET bid_count = 1, highest_bid = 150 WHERE id = ?", id);
        return id;
    }

    private JsonNode submitEvidence(long auctionId) throws Exception {
        return body(mvc.perform(multipart(AUCTIONS + "/{id}/cancellation-requests", auctionId)
                .file(image("evidence")).param("reason", "Card was damaged")
                .header("Authorization", sellerAuth))
            .andExpect(status().isCreated()));
    }

    private void assertStatus(long id, String expected) throws Exception {
        mvc.perform(get(AUCTIONS + "/{id}", id).header("Authorization", sellerAuth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(expected));
    }

    private int heldStock() {
        return jdbc.queryForObject("SELECT auction_reserved FROM local_cards WHERE id = ?", Integer.class, product.getId());
    }

    private long notificationCount(User recipient, String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_id = ? AND notification_type = ?",
            Long.class, recipient.getId(), type);
    }

    private JsonNode body(ResultActions response) throws Exception {
        return json.readTree(response.andReturn().getResponse().getContentAsByteArray());
    }

    private static Instant instant(JsonNode response, String field) {
        return Instant.parse(response.path(field).asText());
    }
}

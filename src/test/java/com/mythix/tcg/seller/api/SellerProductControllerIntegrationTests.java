package com.mythix.tcg.seller.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mythix.tcg.auth.security.JwtService;
import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.domain.OrderItem;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.seller.domain.SellerProfile;
import com.mythix.tcg.user.domain.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SellerProductControllerIntegrationTests {

    private static final String PRODUCTS_PATH = "/api/seller/products";
    private static final String LISTINGS_ALIAS_PATH = "/api/seller/listings";

    private static final String VALID_CREATE_REQUEST = """
        {
          "name": "Umbreon VMAX",
          "game": "Pokemon",
          "image": "https://example.com/umbreon-vmax.webp",
          "price": 1999.90,
          "cost": 1200.25,
          "stock": 4,
          "status": "SHOW",
          "condition": "Near Mint",
          "set": "Evolving Skies",
          "rarity": "Secret Rare"
        }
        """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @PersistenceContext
    private EntityManager entityManager;

    private User seller;
    private User otherSeller;
    private User customer;
    private String sellerToken;
    private String customerToken;

    @BeforeEach
    void seedUsers() {
        seller = persistUser("seller-one@example.com", "USER", "APPROVED");
        otherSeller = persistUser("seller-two@example.com", "USER", "APPROVED");
        customer = persistUser("customer@example.com", "USER", "NONE");
        persistSellerProfile(seller, "Seller One");
        persistSellerProfile(otherSeller, "Seller Two");
        entityManager.flush();

        sellerToken = jwtService.generateToken(seller);
        customerToken = jwtService.generateToken(customer);
    }

    @Test
    void allInventoryOperationsRequireAuthenticationAndSellerRole() throws Exception {
        for (MockHttpServletRequestBuilder request : protectedRequests("999999")) {
            mockMvc.perform(request)
                .andExpect(status().isUnauthorized());
        }

        for (MockHttpServletRequestBuilder request : protectedRequests("999999")) {
            mockMvc.perform(request.header("Authorization", bearer(customerToken)))
                .andExpect(status().isForbidden());
        }

        mockMvc.perform(get(PRODUCTS_PATH).header("Authorization", "Bearer invalid-token"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void approvedSellerStatusAllowsAStaleUserRoleToken() throws Exception {
        User verifiedSeller = persistUser("verified-user-role@example.com", "USER", "APPROVED");
        persistSellerProfile(verifiedSeller, "Verified Legacy Seller");
        entityManager.flush();

        mockMvc.perform(get(PRODUCTS_PATH)
                .header("Authorization", bearer(jwtService.generateToken(verifiedSeller))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    @Test
    void sellerRoleWithoutVerifiedStatusCannotManageInventory() throws Exception {
        User unverifiedSeller = persistUser("unverified@example.com", "USER", "NONE");
        entityManager.flush();

        mockMvc.perform(get(PRODUCTS_PATH)
                .header("Authorization", bearer(jwtService.generateToken(unverifiedSeller))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void getReturnsOnlyCurrentSellerProductsWithFrontendFieldsAndAvailableStock() throws Exception {
        Product ownProduct = persistProduct(
            seller,
            "Pikachu ex",
            "Pokemon",
            "Paldea Evolved",
            "Double Rare",
            "https://example.com/pikachu-master.webp",
            "https://example.com/pikachu-custom.webp",
            new BigDecimal("350.00"),
            new BigDecimal("210.00"),
            7,
            2,
            "SHOW"
        );
        persistProduct(
            otherSeller,
            "Charizard ex",
            "Pokemon",
            "Obsidian Flames",
            "Special Illustration Rare",
            "https://example.com/charizard.webp",
            null,
            new BigDecimal("950.00"),
            new BigDecimal("600.00"),
            3,
            0,
            "SHOW"
        );
        persistOrderItem(ownProduct, "PENDING");
        entityManager.flush();

        String response = mockMvc.perform(get(PRODUCTS_PATH)
                .header("Authorization", bearer(sellerToken)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode rows = objectMapper.readTree(response);
        assertThat(rows.isArray()).isTrue();
        assertThat(rows).hasSize(1);
        assertListing(
            rows.get(0),
            ownProduct.getId(),
            seller.getId(),
            "Pikachu ex",
            "Pokemon",
            "https://example.com/pikachu-custom.webp",
            "350.00",
            "210.00",
            5,
            "SHOW",
            "NM",
            "Paldea Evolved",
            "Double Rare",
            1
        );
    }

    @Test
    void getSupportsFrontendSearchGameStatusAndPaginationParameters() throws Exception {
        Product expected = persistProduct(
            seller,
            "Pikachu V",
            "Pokemon",
            "Vivid Voltage",
            "Ultra Rare",
            "https://example.com/pikachu-v.webp",
            null,
            new BigDecimal("300.00"),
            new BigDecimal("150.00"),
            2,
            0,
            "HIDE"
        );
        persistProduct(
            seller,
            "Eevee",
            "Pokemon",
            "Jungle",
            "Common",
            "https://example.com/eevee.webp",
            null,
            new BigDecimal("50.00"),
            new BigDecimal("20.00"),
            4,
            0,
            "SHOW"
        );
        entityManager.flush();

        String response = mockMvc.perform(get(PRODUCTS_PATH)
                .header("Authorization", bearer(sellerToken))
                .param("search", "pika")
                .param("game", "Pokemon")
                .param("status", "HIDE")
                .param("page", "0")
                .param("size", "10"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode rows = objectMapper.readTree(response);
        assertThat(rows.isArray()).isTrue();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("id").asText()).isEqualTo(String.valueOf(expected.getId()));
    }

    @Test
    void listingsAndVisibilityAliasesRemainCompatibleWithTheFrontendClient() throws Exception {
        Product product = persistProduct(seller, "Alias Test", "Pokemon", "Promo", "Promo",
            "https://example.com/alias.webp", null, new BigDecimal("80.00"), new BigDecimal("30.00"),
            1, 0, "SHOW");
        entityManager.flush();

        mockMvc.perform(get(LISTINGS_ALIAS_PATH).header("Authorization", bearer(sellerToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$[0].id").value(String.valueOf(product.getId())));

        mockMvc.perform(patch(LISTINGS_ALIAS_PATH + "/{id}/visibility", product.getId())
                .header("Authorization", bearer(sellerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"HIDE\"}"))
            .andExpect(status().isNoContent());

        entityManager.flush();
        entityManager.refresh(product);
        assertThat(product.getStatus()).isEqualTo("HIDE");
    }

    @Test
    void postCreatesProductForTokenSellerAndPersistsAllFrontendFields() throws Exception {
        String request = VALID_CREATE_REQUEST.substring(0, VALID_CREATE_REQUEST.lastIndexOf('}'))
            + ",\n  \"sellerId\": \"" + otherSeller.getId() + "\"\n}";

        String response = mockMvc.perform(post(PRODUCTS_PATH)
                .header("Authorization", bearer(sellerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        JsonNode row = objectMapper.readTree(response);
        assertThat(row.path("id").isTextual()).isTrue();
        long productId = Long.parseLong(row.path("id").asText());
        assertListing(
            row,
            productId,
            seller.getId(),
            "Umbreon VMAX",
            "Pokemon",
            "https://example.com/umbreon-vmax.webp",
            "1999.90",
            "1200.25",
            4,
            "SHOW",
            "NM",
            "Evolving Skies",
            "Secret Rare",
            0
        );

        entityManager.flush();
        entityManager.clear();
        Product saved = entityManager.createQuery(
                "select p from Product p join fetch p.seller join fetch p.card where p.id = :id",
                Product.class)
            .setParameter("id", productId)
            .getSingleResult();
        assertThat(saved.getSeller().getId()).isEqualTo(seller.getId());
        assertThat(saved.getCard().getId()).startsWith("seller-listing-");
        assertThat(saved.getCard().getName()).isEqualTo("Umbreon VMAX");
        assertThat(saved.getCard().getSupertype()).isEqualTo("Pokemon");
        assertThat(saved.getCard().getSetName()).isEqualTo("Evolving Skies");
        assertThat(saved.getCard().getRarity()).isEqualTo("Secret Rare");
        assertThat(saved.getCustomImageUrl()).isEqualTo("https://example.com/umbreon-vmax.webp");
        assertThat(saved.getOnHand()).isEqualTo(4);
        assertThat(saved.getReserved()).isZero();
    }

    @Test
    void postRejectsInvalidFrontendFieldsWithStandardValidationShape() throws Exception {
        String invalidRequest = """
            {
              "name": " ",
              "game": " ",
              "image": " ",
              "price": -0.01,
              "cost": -1,
              "stock": -1,
              "status": "ARCHIVED",
              "condition": "Near Mint",
              "set": "Test Set",
              "rarity": "Rare"
            }
            """;

        mockMvc.perform(post(PRODUCTS_PATH)
                .header("Authorization", bearer(sellerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidRequest))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors.name").exists())
            .andExpect(jsonPath("$.fieldErrors.game").exists())
            .andExpect(jsonPath("$.fieldErrors.image").exists())
            .andExpect(jsonPath("$.fieldErrors.price").exists())
            .andExpect(jsonPath("$.fieldErrors.cost").exists())
            .andExpect(jsonPath("$.fieldErrors.stock").exists())
            .andExpect(jsonPath("$.fieldErrors.status").exists());
    }

    @Test
    void postAcceptsFrontendMultipartImageUpload() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
            "image",
            "umbreon.png",
            "image/png",
            new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}
        );

        mockMvc.perform(multipart(PRODUCTS_PATH)
                .file(image)
                .param("name", "Umbreon VMAX")
                .param("game", "Pokemon")
                .param("price", "1999.90")
                .param("cost", "1200.25")
                .param("stock", "4")
                .param("status", "SHOW")
                .param("set", "Evolving Skies")
                .param("rarity", "Secret Rare")
                .header("Authorization", bearer(sellerToken)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.image").value(org.hamcrest.Matchers.matchesPattern(
                "http://localhost:8080/uploads/products/[0-9a-f-]+\\.png"
            )))
            .andExpect(jsonPath("$.condition").value("NM"))
            .andExpect(jsonPath("$.stock").value(4));
    }

    @Test
    void putUpdatesFrontendFieldsAndTreatsStockAsAvailableWithoutDroppingReservations() throws Exception {
        Product product = persistProduct(
            seller,
            "Old Name",
            "Pokemon",
            "Old Set",
            "Common",
            "https://example.com/old.webp",
            null,
            new BigDecimal("100.00"),
            new BigDecimal("50.00"),
            5,
            2,
            "SHOW"
        );
        persistOrderItem(product, "PENDING");
        entityManager.flush();

        String updateRequest = """
            {
              "name": "Updated Name",
              "game": "Pokemon",
              "image": "https://example.com/updated.webp",
              "price": 425.50,
              "cost": 275.25,
              "stock": 9,
              "status": "HIDE",
              "condition": "Lightly Played",
              "set": "Updated Set",
              "rarity": "Illustration Rare"
            }
            """;

        String response = mockMvc.perform(put(PRODUCTS_PATH + "/{id}", product.getId())
                .header("Authorization", bearer(sellerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateRequest))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode row = objectMapper.readTree(response);
        assertListing(
            row,
            product.getId(),
            seller.getId(),
            "Updated Name",
            "Pokemon",
            "https://example.com/updated.webp",
            "425.50",
            "275.25",
            9,
            "HIDE",
            "LP",
            "Updated Set",
            "Illustration Rare",
            1
        );

        entityManager.flush();
        entityManager.clear();
        Product saved = entityManager.find(Product.class, product.getId());
        assertThat(saved.getOnHand()).isEqualTo(11);
        assertThat(saved.getReserved()).isEqualTo(2);
    }

    @Test
    void patchChangesVisibilityAndRejectsUnsupportedStatus() throws Exception {
        Product product = persistProduct(seller, "Mew", "Pokemon", "Promo", "Promo",
            "https://example.com/mew.webp", null, new BigDecimal("80.00"), new BigDecimal("30.00"),
            1, 0, "SHOW");
        entityManager.flush();

        mockMvc.perform(patch(PRODUCTS_PATH + "/{id}/status", product.getId())
                .header("Authorization", bearer(sellerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"HIDE\"}"))
            .andExpect(status().isNoContent());

        entityManager.flush();
        entityManager.refresh(product);
        assertThat(product.getStatus()).isEqualTo("HIDE");

        mockMvc.perform(patch(PRODUCTS_PATH + "/{id}/status", product.getId())
                .header("Authorization", bearer(sellerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DELETED\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors.status").exists());
    }

    @Test
    void putPatchAndDeleteRejectProductsOwnedByAnotherSeller() throws Exception {
        Product foreignProduct = persistProduct(otherSeller, "Foreign", "Pokemon", "Test", "Rare",
            "https://example.com/foreign.webp", null, new BigDecimal("100.00"), new BigDecimal("40.00"),
            2, 0, "HIDE");
        entityManager.flush();

        expectProductError(mockMvc.perform(put(PRODUCTS_PATH + "/{id}", foreignProduct.getId())
                .header("Authorization", bearer(sellerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_CREATE_REQUEST)), 403, "PRODUCT_FORBIDDEN");

        expectProductError(mockMvc.perform(patch(PRODUCTS_PATH + "/{id}/status", foreignProduct.getId())
                .header("Authorization", bearer(sellerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"SHOW\"}")), 403, "PRODUCT_FORBIDDEN");

        expectProductError(mockMvc.perform(delete(PRODUCTS_PATH + "/{id}", foreignProduct.getId())
                .header("Authorization", bearer(sellerToken))), 403, "PRODUCT_FORBIDDEN");
    }

    @Test
    void mutationsReturnNotFoundForMissingProduct() throws Exception {
        expectProductError(mockMvc.perform(put(PRODUCTS_PATH + "/{id}", 999999L)
                .header("Authorization", bearer(sellerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_CREATE_REQUEST)), 404, "PRODUCT_NOT_FOUND");

        expectProductError(mockMvc.perform(patch(PRODUCTS_PATH + "/{id}/status", 999999L)
                .header("Authorization", bearer(sellerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"HIDE\"}")), 404, "PRODUCT_NOT_FOUND");

        expectProductError(mockMvc.perform(delete(PRODUCTS_PATH + "/{id}", 999999L)
                .header("Authorization", bearer(sellerToken))), 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    void deleteRequiresProductToBeHiddenFirst() throws Exception {
        Product product = persistProduct(seller, "Visible", "Pokemon", "Test", "Rare",
            "https://example.com/visible.webp", null, new BigDecimal("100.00"), new BigDecimal("40.00"),
            2, 0, "SHOW");
        entityManager.flush();

        expectProductError(mockMvc.perform(delete(PRODUCTS_PATH + "/{id}", product.getId())
                .header("Authorization", bearer(sellerToken))), 409, "MUST_HIDE");

        assertThat(entityManager.find(Product.class, product.getId())).isNotNull();
    }

    @Test
    void deleteRejectsHiddenProductThatHasAnActiveOrder() throws Exception {
        Product product = persistProduct(seller, "Reserved", "Pokemon", "Test", "Rare",
            "https://example.com/reserved.webp", null, new BigDecimal("100.00"), new BigDecimal("40.00"),
            2, 1, "HIDE");
        persistOrderItem(product, "PENDING");
        entityManager.flush();

        expectProductError(mockMvc.perform(delete(PRODUCTS_PATH + "/{id}", product.getId())
                .header("Authorization", bearer(sellerToken))), 409, "PRODUCT_HAS_ACTIVE_ORDERS");

        assertThat(entityManager.find(Product.class, product.getId())).isNotNull();
    }

    @Test
    void deletePhysicallyRemovesHiddenProductWithoutOrderHistory() throws Exception {
        Product product = persistProduct(seller, "Unused", "Pokemon", "Test", "Common",
            "https://example.com/unused.webp", null, new BigDecimal("20.00"), BigDecimal.ZERO,
            1, 0, "HIDE");
        long productId = product.getId();
        entityManager.flush();

        mockMvc.perform(delete(PRODUCTS_PATH + "/{id}", productId)
                .header("Authorization", bearer(sellerToken)))
            .andExpect(status().isNoContent());

        entityManager.flush();
        entityManager.clear();
        assertThat(entityManager.find(Product.class, productId)).isNull();
    }

    @Test
    void deleteSoftDeletesHiddenProductWithCompletedHistoryAndExcludesItFromInventory() throws Exception {
        Product product = persistProduct(seller, "Historical", "Pokemon", "Test", "Rare",
            "https://example.com/historical.webp", null, new BigDecimal("100.00"), new BigDecimal("40.00"),
            2, 0, "HIDE");
        persistOrderItem(product, "FULFILLED");
        long productId = product.getId();
        entityManager.flush();

        mockMvc.perform(delete(PRODUCTS_PATH + "/{id}", productId)
                .header("Authorization", bearer(sellerToken)))
            .andExpect(status().isNoContent());

        entityManager.flush();
        entityManager.clear();
        Product softDeleted = entityManager.find(Product.class, productId);
        assertThat(softDeleted).isNotNull();
        assertThat(softDeleted.getStatus()).isEqualTo("HIDE");

        mockMvc.perform(get(PRODUCTS_PATH).header("Authorization", bearer(sellerToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].status").value("HIDE"));

        mockMvc.perform(patch(PRODUCTS_PATH + "/{id}/status", productId)
                .header("Authorization", bearer(sellerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"SHOW\"}"))
            .andExpect(status().isNoContent());
    }

    private List<MockHttpServletRequestBuilder> protectedRequests(String id) {
        return List.of(
            get(PRODUCTS_PATH),
            post(PRODUCTS_PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_CREATE_REQUEST),
            put(PRODUCTS_PATH + "/{id}", id).contentType(MediaType.APPLICATION_JSON).content(VALID_CREATE_REQUEST),
            patch(PRODUCTS_PATH + "/{id}/status", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"HIDE\"}"),
            delete(PRODUCTS_PATH + "/{id}", id)
        );
    }

    private void expectProductError(ResultActions result, int expectedStatus, String expectedCode) throws Exception {
        result.andExpect(status().is(expectedStatus))
            .andExpect(jsonPath("$.code").value(expectedCode))
            .andExpect(jsonPath("$.message").isNotEmpty());
    }

    private void assertListing(JsonNode row,
                               Long expectedId,
                               Long expectedSellerId,
                               String expectedName,
                               String expectedGame,
                               String expectedImage,
                               String expectedPrice,
                               String expectedCost,
                               int expectedStock,
                               String expectedStatus,
                               String expectedCondition,
                               String expectedSet,
                               String expectedRarity,
                               int expectedActiveOrderCount) {
        assertThat(row.path("id").isTextual()).isTrue();
        assertThat(row.path("id").asText()).isEqualTo(String.valueOf(expectedId));
        assertThat(row.path("sellerId").isTextual()).isTrue();
        assertThat(row.path("sellerId").asText()).isEqualTo(String.valueOf(expectedSellerId));
        assertThat(row.path("name").asText()).isEqualTo(expectedName);
        assertThat(row.path("game").asText()).isEqualTo(expectedGame);
        assertThat(row.path("image").asText()).isEqualTo(expectedImage);
        assertThat(row.path("price").decimalValue()).isEqualByComparingTo(expectedPrice);
        assertThat(row.path("cost").decimalValue()).isEqualByComparingTo(expectedCost);
        assertThat(row.path("stock").asInt()).isEqualTo(expectedStock);
        assertThat(row.path("status").asText()).isEqualTo(expectedStatus);
        assertThat(row.path("condition").asText()).isEqualTo(expectedCondition);
        assertThat(row.path("set").asText()).isEqualTo(expectedSet);
        assertThat(row.path("rarity").asText()).isEqualTo(expectedRarity);
        assertThat(row.path("activeOrderCount").asInt()).isEqualTo(expectedActiveOrderCount);
    }

    private User persistUser(String email, String role, String sellerStatus) {
        User user = User.builder()
            .email(email)
            .password("test-password-hash")
            .fullName(email)
            .role(role)
            .sellerStatus(sellerStatus)
            .build();
        entityManager.persist(user);
        return user;
    }

    private void persistSellerProfile(User user, String shopName) {
        entityManager.persist(SellerProfile.builder()
            .user(user)
            .shopName(shopName)
            .verifiedAt(LocalDateTime.now())
            .build());
    }

    private Product persistProduct(User owner,
                                   String name,
                                   String game,
                                   String set,
                                   String rarity,
                                   String masterImage,
                                   String customImage,
                                   BigDecimal price,
                                   BigDecimal cost,
                                   int onHand,
                                   int reserved,
                                   String status) {
        Card card = Card.builder()
            .id("seller-listing-test-" + UUID.randomUUID())
            .name(name)
            .supertype(game)
            .setName(set)
            .cardNumber("001")
            .rarity(rarity)
            .imageSmall(masterImage)
            .imageLarge(masterImage)
            .marketPrice(BigDecimal.ZERO)
            .build();
        entityManager.persist(card);

        Product product = Product.builder()
            .seller(owner)
            .card(card)
            .customImageUrl(customImage)
            .condition("NM")
            .language("EN")
            .price(price)
            .cost(cost)
            .onHand(onHand)
            .reserved(reserved)
            .status(status)
            .build();
        entityManager.persist(product);
        return product;
    }

    private void persistOrderItem(Product product, String orderStatus) {
        Order order = Order.builder()
            .orderNumber("TEST-" + UUID.randomUUID())
            .buyer(customer)
            .status(orderStatus)
            .totalAmount(product.getPrice())
            .build();
        entityManager.persist(order);

        OrderItem item = OrderItem.builder()
            .order(order)
            .product(product)
            .seller(product.getSeller())
            .cardId(product.getCard().getId())
            .productNameSnapshot(product.getCard().getName())
            .priceAtPurchase(product.getPrice())
            .costAtPurchase(product.getCost())
            .quantity(1)
            .build();
        entityManager.persist(item);
        order.getItems().add(item);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}

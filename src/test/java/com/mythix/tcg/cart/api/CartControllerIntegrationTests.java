package com.mythix.tcg.cart.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mythix.tcg.auth.security.JwtService;
import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.cart.domain.CartItem;
import com.mythix.tcg.product.domain.Product;
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
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CartControllerIntegrationTests {

    private static final String CART_PATH = "/api/cart";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @PersistenceContext
    private EntityManager entityManager;

    private User buyer;
    private User otherBuyer;
    private User seller;
    private String buyerToken;
    private String otherBuyerToken;
    private String sellerToken;

    @BeforeEach
    void setUp() {
        buyer = persistUser("cart-buyer@example.com", "USER", "NONE");
        otherBuyer = persistUser("cart-other@example.com", "USER", "NONE");
        seller = persistUser("cart-seller@example.com", "SELLER", "APPROVED");
        entityManager.flush();
        buyerToken = jwtService.generateToken(buyer);
        otherBuyerToken = jwtService.generateToken(otherBuyer);
        sellerToken = jwtService.generateToken(seller);
    }

    @Test
    void unauthenticatedCartRequestsReturn401() throws Exception {
        mockMvc.perform(get(CART_PATH))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUserCanReadEmptyCart() throws Exception {
        mockMvc.perform(get(CART_PATH).header("Authorization", bearer(buyerToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.items").isEmpty())
            .andExpect(jsonPath("$.totalItems").value(0))
            .andExpect(jsonPath("$.totalAmount").value(0));
    }

    @Test
    void addCreatesLineAndRepeatedAddIncrementsQuantity() throws Exception {
        Product product = persistProduct(seller, "Pikachu", new BigDecimal("12.50"), 5, 1, "SHOW");
        entityManager.flush();

        mockMvc.perform(post(CART_PATH + "/items")
                .header("Authorization", bearer(buyerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"listingId\":\"" + product.getId() + "\",\"quantity\":2}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.items[0].listingId").value(String.valueOf(product.getId())))
            .andExpect(jsonPath("$.items[0].quantity").value(2))
            .andExpect(jsonPath("$.items[0].available").value(true))
            .andExpect(jsonPath("$.items[0].availableQuantity").value(4));

        mockMvc.perform(post(CART_PATH + "/items")
                .header("Authorization", bearer(buyerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"listingId\":\"" + product.getId() + "\",\"quantity\":2}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.items[0].quantity").value(4));

        assertThat(product.getOnHand()).isEqualTo(5);
        assertThat(product.getReserved()).isEqualTo(1);
    }

    @Test
    void patchSetsAbsoluteQuantityAndDeleteRemovesLine() throws Exception {
        Product product = persistProduct(seller, "Eevee", new BigDecimal("10.00"), 5, 0, "SHOW");
        entityManager.persist(CartItem.builder().user(buyer).product(product).quantity(2).build());
        entityManager.flush();

        mockMvc.perform(patch(CART_PATH + "/items/" + product.getId())
                .header("Authorization", bearer(buyerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":3}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].quantity").value(3));

        mockMvc.perform(delete(CART_PATH + "/items/" + product.getId())
                .header("Authorization", bearer(buyerToken)))
            .andExpect(status().isNoContent());

        mockMvc.perform(get(CART_PATH).header("Authorization", bearer(buyerToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void cartIsIsolatedByAuthenticatedUser() throws Exception {
        Product product = persistProduct(seller, "Mew", new BigDecimal("20.00"), 2, 0, "SHOW");
        entityManager.persist(CartItem.builder().user(buyer).product(product).quantity(1).build());
        entityManager.flush();

        mockMvc.perform(get(CART_PATH).header("Authorization", bearer(otherBuyerToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty());

        mockMvc.perform(delete(CART_PATH + "/items/" + product.getId())
                .header("Authorization", bearer(otherBuyerToken)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_FOUND"));

        mockMvc.perform(patch(CART_PATH + "/items/" + product.getId())
                .header("Authorization", bearer(otherBuyerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":2}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_FOUND"));
    }

    @Test
    void sellerCapableUserCanUseCart() throws Exception {
        Product product = persistProduct(seller, "Seller can buy", new BigDecimal("5.00"), 2, 0, "SHOW");
        entityManager.flush();

        mockMvc.perform(post(CART_PATH + "/items")
                .header("Authorization", bearer(sellerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"listingId\":\"" + product.getId() + "\",\"quantity\":1}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.items[0].quantity").value(1));
    }

    @Test
    void invalidQuantitiesReturnValidationError() throws Exception {
        Product product = persistProduct(seller, "Validation", new BigDecimal("5.00"), 4, 0, "SHOW");
        entityManager.flush();

        for (String body : new String[] {
            "{\"listingId\":\"" + product.getId() + "\",\"quantity\":0}",
            "{\"listingId\":\"" + product.getId() + "\",\"quantity\":-1}",
            "{\"listingId\":\"" + product.getId() + "\",\"quantity\":\"many\"}"
        }) {
            mockMvc.perform(post(CART_PATH + "/items")
                    .header("Authorization", bearer(buyerToken))
                    .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        mockMvc.perform(post(CART_PATH + "/items")
                .header("Authorization", bearer(buyerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"listingId\":\"" + product.getId() + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void allCartMutationsRequireAuthentication() throws Exception {
        mockMvc.perform(post(CART_PATH + "/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"listingId\":\"1\",\"quantity\":1}"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(patch(CART_PATH + "/items/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":1}"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(delete(CART_PATH + "/items/1"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void missingHiddenAndInsufficientListingsReturnExpectedErrors() throws Exception {
        mockMvc.perform(post(CART_PATH + "/items")
                .header("Authorization", bearer(buyerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"listingId\":\"999999\",\"quantity\":1}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("LISTING_NOT_FOUND"));

        Product hidden = persistProduct(seller, "Hidden", new BigDecimal("5.00"), 2, 0, "HIDE");
        Product lowStock = persistProduct(seller, "Low stock", new BigDecimal("5.00"), 2, 1, "SHOW");
        entityManager.flush();

        mockMvc.perform(post(CART_PATH + "/items")
                .header("Authorization", bearer(buyerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"listingId\":\"" + hidden.getId() + "\",\"quantity\":1}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LISTING_NOT_AVAILABLE"));

        mockMvc.perform(post(CART_PATH + "/items")
                .header("Authorization", bearer(buyerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"listingId\":\"" + lowStock.getId() + "\",\"quantity\":2}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
    }

    @Test
    void staleCartKeepsQuantityAndReportsUnavailableWithoutMutatingStock() throws Exception {
        Product product = persistProduct(seller, "Stale", new BigDecimal("7.25"), 5, 0, "SHOW");
        entityManager.persist(CartItem.builder().user(buyer).product(product).quantity(3).build());
        entityManager.flush();
        product.setOnHand(1);
        entityManager.flush();

        mockMvc.perform(get(CART_PATH).header("Authorization", bearer(buyerToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].quantity").value(3))
            .andExpect(jsonPath("$.items[0].available").value(false))
            .andExpect(jsonPath("$.items[0].availableQuantity").value(1));

        assertThat(product.getOnHand()).isEqualTo(1);
        assertThat(product.getReserved()).isZero();
    }

    @Test
    void totalsUseCurrentPriceAndNeverExposeCost() throws Exception {
        Product first = persistProduct(seller, "First", new BigDecimal("12.50"), 4, 0, "SHOW");
        Product second = persistProduct(seller, "Second", new BigDecimal("3.25"), 4, 0, "SHOW");
        entityManager.flush();

        String body = objectMapper.writeValueAsString(new AddCartItemRequest(String.valueOf(first.getId()), 2));
        mockMvc.perform(post(CART_PATH + "/items")
                .header("Authorization", bearer(buyerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());

        mockMvc.perform(post(CART_PATH + "/items")
                .header("Authorization", bearer(buyerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new AddCartItemRequest(String.valueOf(second.getId()), 1))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.totalItems").value(3))
            .andExpect(jsonPath("$.totalAmount").value(28.25))
            .andExpect(jsonPath("$.items[0].cost").doesNotExist());
    }

    private User persistUser(String email, String role, String sellerStatus) {
        User user = User.builder()
            .email(email + "." + UUID.randomUUID())
            .password("test-password-hash")
            .fullName(email)
            .role(role)
            .sellerStatus(sellerStatus)
            .build();
        entityManager.persist(user);
        return user;
    }

    private Product persistProduct(User owner, String name, BigDecimal price, int onHand, int reserved, String status) {
        Card card = Card.builder()
            .id("cart-test-" + UUID.randomUUID())
            .name(name)
            .supertype("Pokemon")
            .setName("Cart Test Set")
            .rarity("Rare")
            .imageSmall("https://example.com/" + name + ".webp")
            .imageLarge("https://example.com/" + name + ".webp")
            .marketPrice(price)
            .build();
        entityManager.persist(card);

        Product product = Product.builder()
            .seller(owner)
            .card(card)
            .condition("Near Mint")
            .language("EN")
            .price(price)
            .cost(new BigDecimal("1.00"))
            .onHand(onHand)
            .reserved(reserved)
            .status(status)
            .build();
        entityManager.persist(product);
        return product;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}

package com.mythix.tcg.order.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mythix.tcg.auth.security.JwtService;
import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.cart.domain.CartItem;
import com.mythix.tcg.cart.persistence.CartItemRepository;
import com.mythix.tcg.collection.persistence.CollectionRepository;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.persistence.OrderItemRepository;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.seller.persistence.SellerProfileRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CheckoutControllerIntegrationTests {
    private static final String CHECKOUT_PATH = "/api/orders";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private CartItemRepository cartItemRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CollectionRepository collectionRepository;
    @Autowired private SellerProfileRepository sellerProfileRepository;
    @PersistenceContext private EntityManager entityManager;

    private User buyer;
    private User seller;
    private String buyerToken;

    @BeforeEach
    void setUp() {
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        collectionRepository.deleteAll();
        productRepository.deleteAll();
        sellerProfileRepository.deleteAll();
        userRepository.deleteAll();
        entityManager.flush();

        buyer = persistUser("checkout-buyer");
        seller = persistUser("checkout-seller");
        seller.setRole("SELLER");
        seller.setSellerStatus("VERIFIED");
        entityManager.flush();
        buyerToken = jwtService.generateToken(buyer);
    }

    @Test
    void checkoutCreatesPendingOrderReservesStockAndClearsCart() throws Exception {
        Product product = persistProduct("Charizard", new BigDecimal("12.50"), 5, 1, "SHOW");
        entityManager.persist(CartItem.builder().user(buyer).product(product).quantity(2).build());
        entityManager.flush();

        mockMvc.perform(post(CHECKOUT_PATH)
                .header("Authorization", bearer(buyerToken))
                .header("Idempotency-Key", "checkout-happy-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CheckoutDto.CheckoutRequest())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isString())
            .andExpect(jsonPath("$.orderNumber").value(org.hamcrest.Matchers.startsWith("ORD-")))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.totalAmount").value(25.00))
            .andExpect(jsonPath("$.items[0].listingId").value(product.getId().toString()))
            .andExpect(jsonPath("$.items[0].quantity").value(2))
            .andExpect(jsonPath("$.items[0].price").value(12.50))
            .andExpect(jsonPath("$.items[0].subtotal").value(25.00))
            .andExpect(jsonPath("$.items[0].cost").doesNotExist());

        entityManager.flush();
        assertThat(productRepository.findById(product.getId()).orElseThrow().getReserved()).isEqualTo(3);
        assertThat(cartItemRepository.findAllByUserIdWithProduct(buyer.getId())).isEmpty();
        assertThat(orderRepository.findByBuyerIdAndIdempotencyKey(buyer.getId(), "checkout-happy-1")).isPresent();
    }

    @Test
    void emptyCartReturnsConflict() throws Exception {
        mockMvc.perform(post(CHECKOUT_PATH)
                .header("Authorization", bearer(buyerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("EMPTY_CART"));
    }

    @Test
    void hiddenListingRollsBackWithoutChangingCartOrStock() throws Exception {
        Product product = persistProduct("Hidden", new BigDecimal("10.00"), 4, 0, "HIDE");
        entityManager.persist(CartItem.builder().user(buyer).product(product).quantity(1).build());
        entityManager.flush();

        mockMvc.perform(post(CHECKOUT_PATH)
                .header("Authorization", bearer(buyerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LISTING_NOT_AVAILABLE"));

        assertThat(orderRepository.findByBuyerIdOrderByCreatedAtDesc(buyer.getId())).isEmpty();
        assertThat(cartItemRepository.findAllByUserIdWithProduct(buyer.getId())).hasSize(1);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getReserved()).isZero();
    }

    @Test
    void idempotentRetryReturnsSameOrderAndDoesNotReserveAgain() throws Exception {
        Product product = persistProduct("Mew", new BigDecimal("8.00"), 3, 0, "SHOW");
        entityManager.persist(CartItem.builder().user(buyer).product(product).quantity(1).build());
        entityManager.flush();

        String first = mockMvc.perform(post(CHECKOUT_PATH)
                .header("Authorization", bearer(buyerToken))
                .header("Idempotency-Key", "retry-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String firstId = objectMapper.readTree(first).get("id").asText();

        mockMvc.perform(post(CHECKOUT_PATH)
                .header("Authorization", bearer(buyerToken))
                .header("Idempotency-Key", "retry-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(firstId));

        assertThat(orderRepository.findByBuyerIdAndIdempotencyKey(buyer.getId(), "retry-key")).hasValueSatisfying(order ->
            assertThat(orderRepository.findByBuyerIdOrderByCreatedAtDesc(buyer.getId())).hasSize(1));
        assertThat(productRepository.findById(product.getId()).orElseThrow().getReserved()).isEqualTo(1);
    }

    @Test
    void checkoutRequiresAuthentication() throws Exception {
        mockMvc.perform(post(CHECKOUT_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized());
    }

    private User persistUser(String prefix) {
        User user = User.builder()
            .email(prefix + "-" + UUID.randomUUID() + "@example.com")
            .password("test-password-hash")
            .fullName(prefix)
            .role("USER")
            .sellerStatus("NONE")
            .build();
        entityManager.persist(user);
        return user;
    }

    private Product persistProduct(String name, BigDecimal price, int onHand, int reserved, String status) {
        Card card = Card.builder()
            .id("checkout-card-" + UUID.randomUUID())
            .name(name)
            .setName("Checkout Set")
            .rarity("Rare")
            .build();
        entityManager.persist(card);
        Product product = Product.builder()
            .seller(seller)
            .card(card)
            .condition("Near Mint")
            .language("EN")
            .price(price)
            .cost(new BigDecimal("3.00"))
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

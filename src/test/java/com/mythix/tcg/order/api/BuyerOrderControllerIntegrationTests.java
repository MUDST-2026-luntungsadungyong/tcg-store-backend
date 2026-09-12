package com.mythix.tcg.order.api;

import com.mythix.tcg.auth.api.RegisterRequest;
import com.mythix.tcg.auth.application.AuthService;
import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.collection.persistence.CollectionRepository;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.domain.OrderItem;
import com.mythix.tcg.order.persistence.OrderItemRepository;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.seller.persistence.SellerProfileRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BuyerOrderControllerIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private CollectionRepository collectionRepository;

    @Autowired
    private SellerProfileRepository sellerProfileRepository;

    private String buyerToken;
    private String otherBuyerToken;
    private User buyer;
    private User seller;
    private Order buyerOrder;

    @BeforeEach
    void setUp() {
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
        sellerProfileRepository.deleteAll();
        collectionRepository.deleteAll();
        userRepository.deleteAll();
        cardRepository.deleteAll();

        // Seller
        var sellerAuth = authService.register(new RegisterRequest("Seller A", "seller_a@example.com", "password123"));
        seller = userRepository.findById(Long.valueOf(sellerAuth.user().id())).orElseThrow();
        seller.setSellerStatus("VERIFIED");
        userRepository.save(seller);

        // Buyer
        var buyerAuth = authService.register(new RegisterRequest("Buyer A", "buyer_a@example.com", "password123"));
        buyerToken = buyerAuth.token();
        buyer = userRepository.findById(Long.valueOf(buyerAuth.user().id())).orElseThrow();

        // Other buyer
        var other = authService.register(new RegisterRequest("Buyer B", "buyer_b@example.com", "password123"));
        otherBuyerToken = other.token();

        // Card
        Card card = Card.builder()
                .id("card-test-001")
                .name("Mewtwo")
                .setName("Base Set")
                .rarity("Rare Holo")
                .imageLarge("https://example.com/mewtwo.png")
                .build();
        cardRepository.save(card);

        // Product
        Product product = Product.builder()
                .card(card)
                .seller(seller)
                .condition("Near Mint")
                .language("EN")
                .price(new BigDecimal("999.00"))
                .cost(new BigDecimal("500.00"))
                .onHand(5)
                .reserved(1)
                .status("SHOW")
                .build();
        productRepository.save(product);

        // Buyer's order
        buyerOrder = Order.builder()
                .orderNumber("ORD-BUYER-001")
                .buyer(buyer)
                .status("RESERVED")
                .totalAmount(new BigDecimal("999.00"))
                .items(new ArrayList<>())
                .build();
        buyerOrder = orderRepository.save(buyerOrder);

        OrderItem item = OrderItem.builder()
                .order(buyerOrder)
                .product(product)
                .seller(seller)
                .cardId(card.getId())
                .productNameSnapshot("Mewtwo NM")
                .priceAtPurchase(new BigDecimal("999.00"))
                .costAtPurchase(new BigDecimal("500.00"))
                .quantity(1)
                .build();
        orderItemRepository.save(item);
    }

    @Test
    @DisplayName("GET /api/orders/me should return 401 when unauthenticated")
    void getOrders_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/orders/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/orders/me should return only buyer's own orders")
    void getOrders_authenticated_returnsOnlyBuyerOrders() throws Exception {
        mockMvc.perform(get("/api/orders/me")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(buyerOrder.getId().toString()))
                .andExpect(jsonPath("$[0].status").value("RESERVED"))
                .andExpect(jsonPath("$[0].totalAmount").value(999.00));
    }

    @Test
    @DisplayName("GET /api/orders/me should return empty list for buyer with no orders")
    void getOrders_buyerWithNoOrders_returnsEmpty() throws Exception {
        mockMvc.perform(get("/api/orders/me")
                        .header("Authorization", "Bearer " + otherBuyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("GET /api/orders/me with status=RESERVED should return filtered results")
    void getOrders_withStatusFilter_returnsFiltered() throws Exception {
        mockMvc.perform(get("/api/orders/me")
                        .header("Authorization", "Bearer " + buyerToken)
                        .param("status", "RESERVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("GET /api/orders/me with status=FULFILLED should return empty")
    void getOrders_withMismatchedStatus_returnsEmpty() throws Exception {
        mockMvc.perform(get("/api/orders/me")
                        .header("Authorization", "Bearer " + buyerToken)
                        .param("status", "FULFILLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("GET /api/orders/{id} should return order detail for owner")
    void getOrderById_owner_returnsDetail() throws Exception {
        mockMvc.perform(get("/api/orders/" + buyerOrder.getId())
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("ORD-BUYER-001"))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productName").value("Mewtwo NM"));
    }

    @Test
    @DisplayName("GET /api/orders/{id} should return 404 when order belongs to different buyer")
    void getOrderById_otherBuyer_returns404() throws Exception {
        mockMvc.perform(get("/api/orders/" + buyerOrder.getId())
                        .header("Authorization", "Bearer " + otherBuyerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }
}

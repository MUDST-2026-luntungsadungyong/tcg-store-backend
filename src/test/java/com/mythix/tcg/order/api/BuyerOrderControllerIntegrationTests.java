package com.mythix.tcg.order.api;

import com.mythix.tcg.auth.api.RegisterRequest;
import com.mythix.tcg.auth.application.AuthService;
import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.cart.persistence.CartItemRepository;
import com.mythix.tcg.collection.persistence.CollectionRepository;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.domain.OrderItem;
import com.mythix.tcg.order.persistence.OrderItemRepository;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.order.persistence.OrderStatusLogRepository;
import com.mythix.tcg.notification.persistence.NotificationRepository;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.seller.persistence.SellerProfileRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

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
    private OrderStatusLogRepository orderStatusLogRepository;

    @Autowired
    private CollectionRepository collectionRepository;

    @Autowired
    private SellerProfileRepository sellerProfileRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private CartItemRepository cartItemRepository;

    private String buyerToken;
    private String otherBuyerToken;
    private User buyer;
    private User seller;
    private Order buyerOrder;
    private Product product;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        orderStatusLogRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        productRepository.deleteAll();
        sellerProfileRepository.deleteAll();
        collectionRepository.deleteAll();
        userRepository.deleteAll();
        cardRepository.deleteAll();

        // Seller
        var sellerAuth = authService.register(new RegisterRequest("Seller A", "seller_a@example.com", "password123"));
        seller = userRepository.findById(Long.valueOf(sellerAuth.user().id())).orElseThrow();
        seller.setSellerStatus("APPROVED");
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
                .supertype("Pokemon")
                .setName("Base Set")
                .cardNumber("001")
                .rarity("Rare Holo")
                .imageSmall("https://example.com/mewtwo-small.png")
                .imageLarge("https://example.com/mewtwo.png")
                .build();
        cardRepository.save(card);

        // Product
        product = Product.builder()
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
                .status("CONFIRMED")
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
                .andExpect(jsonPath("$[0].status").value("CONFIRMED"))
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
    @DisplayName("GET /api/orders/me with status=PAID should return filtered results")
    void getOrders_withStatusFilter_returnsFiltered() throws Exception {
        mockMvc.perform(get("/api/orders/me")
                        .header("Authorization", "Bearer " + buyerToken)
                        .param("status", "CONFIRMED"))
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
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
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

    @Test
    @DisplayName("POST /api/orders/{id}/reorder should add available order items to the current cart")
    void reorder_owner_addsAvailableItemsToCart() throws Exception {
        mockMvc.perform(post("/api/orders/" + buyerOrder.getId() + "/reorder")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAddedItems").value(1))
                .andExpect(jsonPath("$.hasUnavailableItems").value(false))
                .andExpect(jsonPath("$.items[0].outcome").value("ADDED"))
                .andExpect(jsonPath("$.cart.items[0].listingId").value(product.getId().toString()))
                .andExpect(jsonPath("$.cart.items[0].quantity").value(1));
    }

    @Test
    @DisplayName("POST /api/orders/{id}/reorder should report unavailable stock without overfilling the cart")
    void reorder_outOfStock_reportsUnavailableItem() throws Exception {
        product.setOnHand(1);
        product.setReserved(1);
        productRepository.save(product);

        mockMvc.perform(post("/api/orders/" + buyerOrder.getId() + "/reorder")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAddedItems").value(0))
                .andExpect(jsonPath("$.hasUnavailableItems").value(true))
                .andExpect(jsonPath("$.items[0].outcome").value("STOCK_LIMIT_REACHED"))
                .andExpect(jsonPath("$.cart.items").isEmpty());
    }

    @Test
    @DisplayName("POST /api/orders/{id}/reorder should hide another buyer's order")
    void reorder_otherBuyer_returns404() throws Exception {
        mockMvc.perform(post("/api/orders/" + buyerOrder.getId() + "/reorder")
                        .header("Authorization", "Bearer " + otherBuyerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/orders/{id}/invoice-pdf should return an owner-only PDF invoice")
    void invoice_owner_returnsPdf() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/orders/" + buyerOrder.getId() + "/invoice-pdf")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"mythix-order-ORD-BUYER-001.pdf\""))
                .andReturn();

        byte[] pdf = result.getResponse().getContentAsByteArray();
        assertThat(pdf).startsWith("%PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test
    @DisplayName("GET /api/orders/{id}/invoice-pdf should return 404 for another buyer")
    void invoice_otherBuyer_returns404() throws Exception {
        mockMvc.perform(get("/api/orders/" + buyerOrder.getId() + "/invoice-pdf")
                        .header("Authorization", "Bearer " + otherBuyerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/orders/verify/{orderNumber} should expose only minimal public status data")
    void verifyOrder_isPublicAndPrivacySafe() throws Exception {
        mockMvc.perform(get("/api/orders/verify/ORD-BUYER-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("ORD-BUYER-001"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.orderType").value("DIRECT"))
                .andExpect(jsonPath("$.buyerId").doesNotExist())
                .andExpect(jsonPath("$.sellerEmail").doesNotExist())
                .andExpect(jsonPath("$.items").doesNotExist());
    }

    @Test
    void buyerCancellationNotifiesEachSellerWithOrderLink() throws Exception {
        mockMvc.perform(patch("/api/orders/" + buyerOrder.getId() + "/status")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(notificationRepository.findAll()).anySatisfy(notification -> {
            assertThat(notification.getRecipient().getId()).isEqualTo(seller.getId());
            assertThat(notification.getOrder().getId()).isEqualTo(buyerOrder.getId());
            assertThat(notification.getType()).isEqualTo("ORDER_CANCELLED_BUYER");
            assertThat(notification.getActionPath()).isEqualTo("/workspace/orders?orderId=" + buyerOrder.getId());
        });
    }

    @Test
    void buyerFulfillmentNotifiesSellerWithOrderLink() throws Exception {
        buyerOrder.setStatus("SENT");
        orderRepository.save(buyerOrder);

        mockMvc.perform(patch("/api/orders/" + buyerOrder.getId() + "/status")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FULFILLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULFILLED"));

        assertThat(notificationRepository.findAll()).anySatisfy(notification -> {
            assertThat(notification.getRecipient().getId()).isEqualTo(seller.getId());
            assertThat(notification.getOrder().getId()).isEqualTo(buyerOrder.getId());
            assertThat(notification.getType()).isEqualTo("ORDER_FULFILLED_SELLER");
            assertThat(notification.getActionPath()).isEqualTo("/workspace/orders?orderId=" + buyerOrder.getId());
        });
    }
}



package com.mythix.tcg.order.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mythix.tcg.auth.application.AuthService;
import com.mythix.tcg.auth.api.RegisterRequest;
import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.domain.OrderItem;
import com.mythix.tcg.order.persistence.OrderItemRepository;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.notification.persistence.NotificationRepository;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.seller.persistence.SellerProfileRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SellerOrderControllerIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private SellerProfileRepository sellerProfileRepository;

    @Autowired
    private com.mythix.tcg.collection.persistence.CollectionRepository collectionRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    private String sellerToken;
    private User seller;
    private User otherSeller;
    private User buyer;
    private Card card;
    private Product sellerProduct;
    private Product otherProduct;
    private Order sellerOrder;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        collectionRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
        sellerProfileRepository.deleteAll();
        userRepository.deleteAll();
        cardRepository.deleteAll();

        // 1. Create seller
        var sellerAuth = authService.register(new RegisterRequest("Seller John", "seller@example.com", "password123"));
        sellerToken = sellerAuth.token();
        seller = userRepository.findById(Long.valueOf(sellerAuth.user().id())).orElseThrow();
        seller.setSellerStatus("APPROVED");
        userRepository.save(seller);

        // 2. Create other seller
        var otherAuth = authService.register(new RegisterRequest("Other Seller", "other@example.com", "password123"));
        otherSeller = userRepository.findById(Long.valueOf(otherAuth.user().id())).orElseThrow();

        // 3. Create buyer
        var buyerAuth = authService.register(new RegisterRequest("Buyer Bob", "buyer@example.com", "password123"));
        buyer = userRepository.findById(Long.valueOf(buyerAuth.user().id())).orElseThrow();
        buyer.setPhone("0812345678");
        buyer.setContactLine("bob_line");
        userRepository.save(buyer);

        // 4. Create card
        card = Card.builder()
                .id("card-001")
                .name("Blue-Eyes White Dragon")
                .supertype("YGO")
                .setName("Legend of Blue Eyes")
                .cardNumber("001")
                .rarity("Ultra Rare")
                .imageSmall("https://example.com/blueeyes-small.png")
                .imageLarge("https://example.com/blueeyes.png")
                .build();
        card = cardRepository.save(card);

        // 5. Create products
        sellerProduct = Product.builder()
                .card(card)
                .seller(seller)
                .condition("Near Mint")
                .language("EN")
                .price(new BigDecimal("150.00"))
                .cost(new BigDecimal("80.00"))
                .onHand(10)
                .reserved(2)
                .status("SHOW")
                .build();
        sellerProduct = productRepository.save(sellerProduct);

        otherProduct = Product.builder()
                .card(card)
                .seller(otherSeller)
                .condition("Near Mint")
                .language("EN")
                .price(new BigDecimal("500.00"))
                .cost(new BigDecimal("300.00"))
                .onHand(5)
                .reserved(1)
                .status("SHOW")
                .build();
        otherProduct = productRepository.save(otherProduct);

        // 6. Create order for seller
        sellerOrder = Order.builder()
                .orderNumber("ORD-TEST-001")
                .buyer(buyer)
                .status("PENDING")
                .totalAmount(new BigDecimal("300.00"))
                .items(new ArrayList<>())
                .build();
        sellerOrder = orderRepository.save(sellerOrder);

        OrderItem item1 = OrderItem.builder()
                .order(sellerOrder)
                .product(sellerProduct)
                .seller(seller)
                .cardId(card.getId())
                .productNameSnapshot("Blue-Eyes White Dragon NM")
                .priceAtPurchase(new BigDecimal("150.00"))
                .costAtPurchase(new BigDecimal("80.00"))
                .quantity(2)
                .build();
        orderItemRepository.save(item1);
        sellerOrder.getItems().add(item1);

        // 7. Create order for other seller
        Order otherOrder = Order.builder()
                .orderNumber("ORD-OTHER-002")
                .buyer(buyer)
                .status("PENDING")
                .totalAmount(new BigDecimal("500.00"))
                .items(new ArrayList<>())
                .build();
        otherOrder = orderRepository.save(otherOrder);

        OrderItem item2 = OrderItem.builder()
                .order(otherOrder)
                .product(otherProduct)
                .seller(otherSeller)
                .cardId(card.getId())
                .productNameSnapshot("Dark Magician")
                .priceAtPurchase(new BigDecimal("500.00"))
                .costAtPurchase(new BigDecimal("300.00"))
                .quantity(1)
                .build();
        orderItemRepository.save(item2);
        otherOrder.getItems().add(item2);
    }

    @Test
    void getSellerOrders_ReturnsOnlySellerOrders() throws Exception {
        mockMvc.perform(get("/api/seller/orders")
                .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].orderNumber").value("ORD-TEST-001"))
                .andExpect(jsonPath("$.content[0].buyerName").value("Buyer Bob"))
                .andExpect(jsonPath("$.content[0].buyerPhone").value("0812345678"))
                .andExpect(jsonPath("$.content[0].buyerContactLine").value("bob_line"))
                .andExpect(jsonPath("$.content[0].sellerRevenue").value(300.00))
                .andExpect(jsonPath("$.content[0].sellerCost").value(160.00))
                .andExpect(jsonPath("$.content[0].sellerProfit").value(140.00))
                .andExpect(jsonPath("$.content[0].items.length()").value(1))
                .andExpect(jsonPath("$.content[0].items[0].productName").value("Blue-Eyes White Dragon NM"));
    }

    @Test
    void updateOrderStatus_ChangesStatusSuccessfully() throws Exception {
        mockMvc.perform(patch("/api/seller/orders/" + sellerOrder.getId() + "/status")
                .header("Authorization", "Bearer " + sellerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"FULFILLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULFILLED"));

        Order updated = orderRepository.findById(sellerOrder.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("FULFILLED");

        // Verify stock deducted in database: onHand was 10, now 8; reserved was 2, now 0
        Product updatedProduct = productRepository.findById(sellerProduct.getId()).orElseThrow();
        assertThat(updatedProduct.getOnHand()).isEqualTo(8);
        assertThat(updatedProduct.getReserved()).isEqualTo(0);

        assertThat(notificationRepository.findAll())
            .anySatisfy(notification -> {
                assertThat(notification.getRecipient().getId()).isEqualTo(buyer.getId());
                assertThat(notification.getOrder().getId()).isEqualTo(sellerOrder.getId());
                assertThat(notification.getType()).isEqualTo("ORDER_STATUS_CHANGED");
            });
    }

    @Test
    void updateOrderStatus_Cancelled_ReleasesReservedStock() throws Exception {
        mockMvc.perform(patch("/api/seller/orders/" + sellerOrder.getId() + "/status")
                .header("Authorization", "Bearer " + sellerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        Order updated = orderRepository.findById(sellerOrder.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("CANCELLED");

        // Verify reserved stock released: onHand remains 10, reserved was 2 now 0
        Product updatedProduct = productRepository.findById(sellerProduct.getId()).orElseThrow();
        assertThat(updatedProduct.getOnHand()).isEqualTo(10);
        assertThat(updatedProduct.getReserved()).isEqualTo(0);
    }

    @Test
    void updateOrderStatus_TerminalStatusCannotChange() throws Exception {
        // Mark as FULFILLED first
        mockMvc.perform(patch("/api/seller/orders/" + sellerOrder.getId() + "/status")
                .header("Authorization", "Bearer " + sellerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"FULFILLED\"}"))
                .andExpect(status().isOk());

        // Attempt to cancel an already fulfilled order should fail
        mockMvc.perform(patch("/api/seller/orders/" + sellerOrder.getId() + "/status")
                .header("Authorization", "Bearer " + sellerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void updateOrderStatusRejectsFrontendOnlyStatusValues() throws Exception {
        mockMvc.perform(patch("/api/seller/orders/{orderId}/status", sellerOrder.getId())
                .header("Authorization", "Bearer " + sellerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"SENT\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getProfitSummary_ComputesMetricsAccurately() throws Exception {
        mockMvc.perform(get("/api/seller/orders/profit")
                .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRevenue").value(300.00))
                .andExpect(jsonPath("$.totalCost").value(160.00))
                .andExpect(jsonPath("$.netProfit").value(140.00))
                .andExpect(jsonPath("$.totalOrders").value(1))
                .andExpect(jsonPath("$.pendingOrders").value(1))
                .andExpect(jsonPath("$.fulfilledOrders").value(0))
                .andExpect(jsonPath("$.cancelledOrders").value(0));
    }

    @Test
    void unauthorizedAccess_RejectsWithoutToken() throws Exception {
        mockMvc.perform(get("/api/seller/orders"))
                .andExpect(status().isUnauthorized());
    }
}

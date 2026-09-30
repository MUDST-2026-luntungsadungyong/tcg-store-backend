package com.mythix.tcg.notification.api;

import com.mythix.tcg.auth.api.RegisterRequest;
import com.mythix.tcg.auth.application.AuthService;
import com.mythix.tcg.notification.domain.UserNotification;
import com.mythix.tcg.notification.persistence.NotificationRepository;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationControllerIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private ObjectMapper objectMapper;

    private User buyer;
    private User seller;
    private String buyerToken;
    private String sellerToken;
    private Order order;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        orderRepository.deleteAll();
        userRepository.findByEmail("notifications-buyer@example.com").ifPresent(userRepository::delete);
        userRepository.findByEmail("notifications-seller@example.com").ifPresent(userRepository::delete);

        var buyerAuth = authService.register(new RegisterRequest("Buyer", "notifications-buyer@example.com", "password123"));
        buyerToken = buyerAuth.token();
        buyer = userRepository.findById(Long.valueOf(buyerAuth.user().id())).orElseThrow();

        var sellerAuth = authService.register(new RegisterRequest("Seller", "notifications-seller@example.com", "password123"));
        sellerToken = sellerAuth.token();
        seller = userRepository.findById(Long.valueOf(sellerAuth.user().id())).orElseThrow();

        order = orderRepository.save(Order.builder()
            .orderNumber("ORD-NOTIFICATION-1")
            .buyer(buyer)
            .status("PENDING")
            .totalAmount(new BigDecimal("10.00"))
            .build());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/notifications"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void listsOnlyCurrentUsersNotificationsWithPagination() throws Exception {
        createNotification(seller, "ORDER_RESERVED", "New reservation");
        createNotification(buyer, "ORDER_STATUS_CHANGED", "Order sent");

        mockMvc.perform(get("/api/notifications")
                .header("Authorization", bearer(buyerToken))
                .param("page", "0")
                .param("size", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].type").value("ORDER_STATUS_CHANGED"))
            .andExpect(jsonPath("$.content[0].title").value("Order sent"))
            .andExpect(jsonPath("$.content[0].orderId").value(order.getId()))
            .andExpect(jsonPath("$.content[0].readAt").isEmpty())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.number").value(0))
            .andExpect(jsonPath("$.size").value(10))
            .andExpect(jsonPath("$.content[0].recipientId").doesNotExist());
    }

    @Test
    void returnsAnEmptyPageWhenThereAreNoNotifications() throws Exception {
        mockMvc.perform(get("/api/notifications")
                .header("Authorization", bearer(buyerToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void rejectsInvalidPagination() throws Exception {
        mockMvc.perform(get("/api/notifications")
                .header("Authorization", bearer(buyerToken))
                .param("page", "-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/notifications")
                .header("Authorization", bearer(buyerToken))
                .param("size", "101"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void marksOwnedNotificationAsReadIdempotently() throws Exception {
        UserNotification notification = createNotification(buyer, "ORDER_STATUS_CHANGED", "Order sent");

        var firstResponse = mockMvc.perform(patch("/api/notifications/{id}/read", notification.getId())
                .header("Authorization", bearer(buyerToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.readAt").isNotEmpty())
            .andReturn();
        var firstReadAt = objectMapper.readTree(firstResponse.getResponse().getContentAsString())
            .get("readAt")
            .asText();

        var secondResponse = mockMvc.perform(patch("/api/notifications/{id}/read", notification.getId())
                .header("Authorization", bearer(buyerToken)))
            .andExpect(status().isOk())
            .andReturn();
        var secondReadAt = objectMapper.readTree(secondResponse.getResponse().getContentAsString())
            .get("readAt")
            .asText();
        assertThat(LocalDateTime.parse(secondReadAt))
            .isCloseTo(LocalDateTime.parse(firstReadAt), within(1, ChronoUnit.MILLIS));
    }

    @Test
    void cannotReadOrDeleteAnotherUsersNotification() throws Exception {
        UserNotification notification = createNotification(seller, "ORDER_RESERVED", "New reservation");

        mockMvc.perform(patch("/api/notifications/{id}/read", notification.getId())
                .header("Authorization", bearer(buyerToken)))
            .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/notifications/{id}", notification.getId())
                .header("Authorization", bearer(buyerToken)))
            .andExpect(status().isNotFound());
        assertThat(notificationRepository.existsById(notification.getId())).isTrue();
    }

    @Test
    void deletesOwnedNotification() throws Exception {
        UserNotification notification = createNotification(buyer, "ORDER_STATUS_CHANGED", "Order sent");

        mockMvc.perform(delete("/api/notifications/{id}", notification.getId())
                .header("Authorization", bearer(buyerToken)))
            .andExpect(status().isNoContent());

        assertThat(notificationRepository.existsById(notification.getId())).isFalse();
    }

    @Test
    void readAndDeleteRequireAuthentication() throws Exception {
        UserNotification notification = createNotification(buyer, "ORDER_STATUS_CHANGED", "Order sent");
        mockMvc.perform(patch("/api/notifications/{id}/read", notification.getId()))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/notifications/{id}", notification.getId()))
            .andExpect(status().isUnauthorized());
    }

    private UserNotification createNotification(User recipient, String type, String title) {
        return notificationRepository.save(UserNotification.builder()
            .recipient(recipient)
            .order(order)
            .type(type)
            .title(title)
            .message("Order update")
            .build());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}

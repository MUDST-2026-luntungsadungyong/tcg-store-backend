package com.mythix.tcg.order.application;

import com.mythix.tcg.order.api.BuyerOrderDto.BuyerOrderResponse;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.domain.OrderItem;
import com.mythix.tcg.order.persistence.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.mythix.tcg.order.persistence.OrderStatusLogRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuyerOrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStatusLogRepository orderStatusLogRepository;

    @Mock
    private com.mythix.tcg.product.persistence.ProductRepository productRepository;

    @Mock
    private com.mythix.tcg.user.persistence.UserRepository userRepository;

    @Mock
    private com.mythix.tcg.collection.application.CollectionService collectionService;

    @InjectMocks
    private BuyerOrderService buyerOrderService;

    private Order order1;
    private Order order2;

    @BeforeEach
    void setUp() {
        order1 = Order.builder()
                .id(101L)
                .orderNumber("ORD-001")
                .status("RESERVED")
                .totalAmount(new BigDecimal("500.00"))
                .items(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        order2 = Order.builder()
                .id(102L)
                .orderNumber("ORD-002")
                .status("FULFILLED")
                .totalAmount(new BigDecimal("1200.00"))
                .items(new ArrayList<>())
                .createdAt(LocalDateTime.now().minusDays(1))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .build();
    }

    @Test
    @DisplayName("getBuyerOrders should return all orders for buyer when no status filter")
    void getBuyerOrders_noFilter_returnsAllOrders() {
        when(orderRepository.findByBuyerIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(order1, order2));

        List<BuyerOrderResponse> result = buyerOrderService.getBuyerOrders(1L, null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo("101");
        assertThat(result.get(0).getStatus()).isEqualTo("RESERVED");
        assertThat(result.get(1).getId()).isEqualTo("102");
    }

    @Test
    @DisplayName("getBuyerOrders should filter orders by status")
    void getBuyerOrders_withStatusFilter_returnsFilteredOrders() {
        when(orderRepository.findByBuyerIdAndStatusOrderByCreatedAtDesc(1L, "FULFILLED"))
                .thenReturn(List.of(order2));

        List<BuyerOrderResponse> result = buyerOrderService.getBuyerOrders(1L, "FULFILLED");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo("FULFILLED");
    }

    @Test
    @DisplayName("getBuyerOrders should return empty list when buyer has no orders")
    void getBuyerOrders_noOrders_returnsEmpty() {
        when(orderRepository.findByBuyerIdOrderByCreatedAtDesc(99L))
                .thenReturn(List.of());

        List<BuyerOrderResponse> result = buyerOrderService.getBuyerOrders(99L, null);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getBuyerOrderById should return order when it belongs to buyer")
    void getBuyerOrderById_happyPath_returnsOrder() {
        when(orderRepository.findByIdAndBuyerId(101L, 1L))
                .thenReturn(Optional.of(order1));

        BuyerOrderResponse result = buyerOrderService.getBuyerOrderById(1L, 101L);

        assertThat(result.getId()).isEqualTo("101");
        assertThat(result.getOrderNumber()).isEqualTo("ORD-001");
        assertThat(result.getTotalAmount()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("getBuyerOrderById should throw OrderNotFoundException when order belongs to different buyer")
    void getBuyerOrderById_differentBuyer_throwsOrderNotFoundException() {
        when(orderRepository.findByIdAndBuyerId(101L, 99L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> buyerOrderService.getBuyerOrderById(99L, 101L))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining("101");
    }
}


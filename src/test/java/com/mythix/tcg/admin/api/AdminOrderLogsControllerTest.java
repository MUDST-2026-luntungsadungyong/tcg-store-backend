package com.mythix.tcg.admin.api;

import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.domain.OrderItem;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Admin: AdminOrderLogsController Unit Tests")
class AdminOrderLogsControllerTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private AdminOrderLogsController controller;

    private Order createSampleOrder() {
        User buyer = User.builder()
                .id(1L)
                .fullName("Ash Ketchum")
                .email("ash@mythix.com")
                .role("USER")
                .sellerStatus("NONE")
                .build();

        User seller = User.builder()
                .id(2L)
                .fullName("Brock Pewter")
                .email("brock@mythix.com")
                .role("USER")
                .sellerStatus("APPROVED")
                .build();

        OrderItem item = OrderItem.builder()
                .id(10L)
                .productNameSnapshot("Charizard VMAX")
                .cardId("swsh45-SV107")
                .seller(seller)
                .shopNameSnapshot("Pewter Gym Cards")
                .priceAtPurchase(new BigDecimal("3500.00"))
                .quantity(1)
                .build();

        return Order.builder()
                .id(1001L)
                .orderNumber("ORD-2026-001")
                .buyer(buyer)
                .status("FULFILLED")
                .totalAmount(new BigDecimal("3500.00"))
                .platformFee(new BigDecimal("175.00"))
                .sellerNetAmount(new BigDecimal("3325.00"))
                .items(List.of(item))
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("orders() - returns paged order logs without filters")
    void orders_noFilter_returnsAllLogs() {
        Order sampleOrder = createSampleOrder();
        Page<Order> pagedOrders = new PageImpl<>(List.of(sampleOrder), PageRequest.of(0, 20), 1);

        when(orderRepository.searchAdminOrderLogs(
                eq(""), eq("ORDER_NUMBER"), eq(List.of("__NO_STATUS_FILTER__")), eq("Y"), any(), any(), any(PageRequest.class)
        )).thenReturn(pagedOrders);

        Page<AdminOrderLogResponse> result = controller.orders(null, "ORDER_NUMBER", null, null, null, 0, 20);

        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        AdminOrderLogResponse log = result.getContent().get(0);
        assertThat(log.id()).isEqualTo("1001");
        assertThat(log.orderNumber()).isEqualTo("ORD-2026-001");
        assertThat(log.buyerName()).isEqualTo("Ash Ketchum");
        assertThat(log.status()).isEqualTo("FULFILLED");
        assertThat(log.totalAmount()).isEqualByComparingTo("3500.00");
        assertThat(log.items()).hasSize(1);
        assertThat(log.items().get(0).productName()).isEqualTo("Charizard VMAX");
        assertThat(log.items().get(0).sellerName()).isEqualTo("Brock Pewter");

        verify(orderRepository).searchAdminOrderLogs(
                eq(""), eq("ORDER_NUMBER"), eq(List.of("__NO_STATUS_FILTER__")), eq("Y"), any(), any(), any(PageRequest.class)
        );
    }

    @Test
    @DisplayName("orders() - normalizes searchBy to ORDER_NUMBER when an unrecognized key is passed")
    void orders_invalidSearchBy_normalizesToOrderNumber() {
        Order sampleOrder = createSampleOrder();
        Page<Order> pagedOrders = new PageImpl<>(List.of(sampleOrder));

        when(orderRepository.searchAdminOrderLogs(
                eq(""), eq("ORDER_NUMBER"), eq(List.of("__NO_STATUS_FILTER__")), eq("Y"), any(), any(), any(PageRequest.class)
        )).thenReturn(pagedOrders);

        Page<AdminOrderLogResponse> result = controller.orders("", "INVALID_KEY", null, null, null, 0, 20);

        assertThat(result.getContent()).hasSize(1);
        verify(orderRepository).searchAdminOrderLogs(
                eq(""), eq("ORDER_NUMBER"), eq(List.of("__NO_STATUS_FILTER__")), eq("Y"), any(), any(), any(PageRequest.class)
        );
    }

    @Test
    @DisplayName("orders() - supports filtering by statuses and date range")
    void orders_withStatusesAndDateRange_appliesFilters() {
        Order sampleOrder = createSampleOrder();
        Page<Order> pagedOrders = new PageImpl<>(List.of(sampleOrder));

        when(orderRepository.searchAdminOrderLogs(
                eq("ORD-2026"), eq("ORDER_NUMBER"), eq(List.of("FULFILLED")), eq("N"), any(), any(), any(PageRequest.class)
        )).thenReturn(pagedOrders);

        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 31);

        Page<AdminOrderLogResponse> result = controller.orders("ORD-2026", "ORDER_NUMBER", List.of("FULFILLED"), from, to, 0, 10);

        assertThat(result.getContent()).hasSize(1);
        verify(orderRepository).searchAdminOrderLogs(
                eq("ORD-2026"), eq("ORDER_NUMBER"), eq(List.of("FULFILLED")), eq("N"), any(), any(), any(PageRequest.class)
        );
    }
}

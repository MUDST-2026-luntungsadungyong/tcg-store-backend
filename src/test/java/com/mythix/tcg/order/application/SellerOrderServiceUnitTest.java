package com.mythix.tcg.order.application;

import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.order.api.SellerOrderDto.SellerOrderResponse;
import com.mythix.tcg.order.api.SellerOrderDto.SellerProfitSummaryResponse;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.domain.OrderItem;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Sprint 1: SellerOrderService Unit Tests (SCRUM-26)")
class SellerOrderServiceUnitTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private SellerOrderService sellerOrderService;

    private User buyer;
    private User seller;
    private Order sampleOrder;

    @BeforeEach
    void setUp() {
        buyer = User.builder().id(1L).email("buyer@mythix.com").fullName("Ash").phone("081-111-1111").build();
        seller = User.builder().id(2L).email("seller@mythix.com").fullName("Brock").phone("089-999-9999").role("SELLER").sellerStatus("VERIFIED").build();

        Card card = Card.builder().id("pop4-4").name("Mew").build();
        Product product = Product.builder().id(10L).seller(seller).card(card).price(new BigDecimal("1250.00")).cost(new BigDecimal("800.00")).build();

        sampleOrder = Order.builder()
            .id(100L)
            .orderNumber("ORD-2026-0001")
            .buyer(buyer)
            .status("PENDING")
            .totalAmount(new BigDecimal("1250.00"))
            .createdAt(LocalDateTime.now())
            .items(new ArrayList<>())
            .build();

        OrderItem item = OrderItem.builder()
            .id(500L)
            .order(sampleOrder)
            .product(product)
            .seller(seller)
            .cardId("pop4-4")
            .productNameSnapshot("Mew")
            .priceAtPurchase(new BigDecimal("1250.00"))
            .costAtPurchase(new BigDecimal("800.00"))
            .quantity(1)
            .build();

        sampleOrder.getItems().add(item);
    }

    @Test
    @DisplayName("getSellerOrders() - ดึงรายการคำสั่งซื้อของผู้ขายสำเร็จ")
    void getSellerOrders_all_success() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 10);
        when(orderRepository.findOrdersBySellerId(2L, pageable))
            .thenReturn(new PageImpl<>(List.of(sampleOrder), pageable, 1));

        // Act
        Page<SellerOrderResponse> result = sellerOrderService.getSellerOrders(2L, "ALL", pageable);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).orderNumber()).isEqualTo("ORD-2026-0001");
        assertThat(result.getContent().get(0).items()).hasSize(1);
    }

    @Test
    @DisplayName("getSellerOrders() - กรองรายการคำสั่งซื้อตามสถานะสำเร็จ")
    void getSellerOrders_filtered_success() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 10);
        when(orderRepository.findOrdersBySellerIdAndStatus(2L, "PENDING", pageable))
            .thenReturn(new PageImpl<>(List.of(sampleOrder), pageable, 1));

        // Act
        Page<SellerOrderResponse> result = sellerOrderService.getSellerOrders(2L, "PENDING", pageable);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(orderRepository).findOrdersBySellerIdAndStatus(2L, "PENDING", pageable);
    }

    @Test
    @DisplayName("getSellerOrderById() - ส่งคืนข้อมูลคำสั่งซื้อเมื่อพบ Order ID ของผู้ขาย")
    void getSellerOrderById_success() {
        // Arrange
        when(orderRepository.findOrderForSeller(2L, 100L)).thenReturn(Optional.of(sampleOrder));

        // Act
        SellerOrderResponse response = sellerOrderService.getSellerOrderById(2L, 100L);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.orderNumber()).isEqualTo("ORD-2026-0001");
        assertThat(response.sellerRevenue()).isEqualByComparingTo("1250.00");
    }

    @Test
    @DisplayName("getSellerOrderById() - โยน ResponseStatusException (404) เมื่อไม่พบ Order ของผู้ขาย")
    void getSellerOrderById_notFound_throwsException() {
        // Arrange
        when(orderRepository.findOrderForSeller(2L, 999L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> sellerOrderService.getSellerOrderById(2L, 999L))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("updateOrderStatus() - อัปเดตสถานะคำสั่งซื้อสำเร็จ")
    void updateOrderStatus_success() {
        // Arrange
        when(orderRepository.findOrderForSeller(2L, 100L)).thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenReturn(sampleOrder);

        // Act
        SellerOrderResponse response = sellerOrderService.updateOrderStatus(2L, 100L, "CONFIRMED");

        // Assert
        assertThat(response).isNotNull();
        assertThat(sampleOrder.getStatus()).isEqualTo("CONFIRMED");
        verify(orderRepository).save(sampleOrder);
    }

    @Test
    @DisplayName("getProfitSummary() - คำนวณยอดขาย ต้นทุน และกำไรรวมของผู้ขายถูกต้อง")
    void getProfitSummary_success() {
        // Arrange
        when(orderRepository.findAllOrdersBySellerId(2L)).thenReturn(List.of(sampleOrder));

        // Act
        SellerProfitSummaryResponse summary = sellerOrderService.getProfitSummary(2L);

        // Assert
        assertThat(summary).isNotNull();
        assertThat(summary.totalRevenue()).isEqualByComparingTo("1250.00");
        assertThat(summary.totalCost()).isEqualByComparingTo("800.00");
        assertThat(summary.netProfit()).isEqualByComparingTo("450.00");
        assertThat(summary.totalOrders()).isEqualTo(1);
    }
}

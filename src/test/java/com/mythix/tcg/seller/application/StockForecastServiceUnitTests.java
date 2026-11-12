package com.mythix.tcg.seller.application;

import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.seller.api.StockForecastResponse;
import com.mythix.tcg.seller.persistence.SellerOrderItemRepository;
import com.mythix.tcg.seller.persistence.SellerOrderItemRepository.ProductSalesTotal;
import com.mythix.tcg.seller.persistence.SellerProductRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockForecastServiceUnitTests {

    @Mock
    private SellerProductRepository productRepository;

    @Mock
    private SellerOrderItemRepository orderItemRepository;

    @Mock
    private UserRepository userRepository;

    private StockForecastService service;
    private User seller;

    @BeforeEach
    void setUp() {
        service = new StockForecastService(productRepository, orderItemRepository, userRepository);
        seller = User.builder().id(10L).email("seller@mythix.com").sellerStatus("APPROVED").build();
    }

    @Test
    void calculatesUrgenciesAvailableStockAndZeroSalesSafely() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(seller));

        Product critical = product(1L, "Charizard", 6, 1, 1, "SHOW"); // available = 4
        Product safe = product(2L, "Pikachu", 20, 0, 0, "SHOW"); // available = 20
        Product noSales = product(3L, "Mewtwo", 5, 0, 0, "SHOW"); // available = 5, 0 sold
        Product outOfStock = product(4L, "Blastoise", 1, 1, 0, "SHOW"); // available = 0
        Product hidden = product(5L, "Gengar", 2, 0, 0, "HIDE");

        when(productRepository.findAllBySeller_IdAndStatusNotOrderByUpdatedAtDescIdDesc(eq(10L), any()))
            .thenReturn(List.of(critical, safe, noSales, outOfStock, hidden));

        // Simple average over 30 days:
        // Charizard: 30 sold -> 1.00/day -> 4 days left -> CRITICAL (leadTime 7)
        // Pikachu: 15 sold -> 0.50/day -> 40 days left -> SAFE
        // Mewtwo: 0 sold -> NO_RECENT_SALES (daysUntilStockout = null)
        // Blastoise: 10 sold -> available = 0 -> OUT_OF_STOCK
        List<ProductSalesTotal> totals = List.of(
            salesRow(1L, 30L),
            salesRow(2L, 15L),
            salesRow(4L, 10L)
        );
        when(orderItemRepository.sumDirectUnitsSoldSince(eq(10L), any(LocalDateTime.class))).thenReturn(totals);

        StockForecastResponse response = service.getForecast(10L, 30, "SIMPLE", 7, 3, 30, false, null, "ALL");

        assertThat(response.summary().totalSkus()).isEqualTo(4);
        assertThat(response.summary().outOfStockCount()).isEqualTo(1);
        assertThat(response.summary().criticalCount()).isEqualTo(1);
        assertThat(response.summary().safeCount()).isEqualTo(1);
        assertThat(response.summary().noRecentSalesCount()).isEqualTo(1);

        assertThat(response.items()).extracting(StockForecastResponse.Item::name)
            .containsExactly("Blastoise", "Charizard", "Pikachu", "Mewtwo");

        StockForecastResponse.Item charizardItem = response.items().get(1);
        assertThat(charizardItem.availableStock()).isEqualTo(4);
        assertThat(charizardItem.dailySalesRate()).isEqualByComparingTo("1.00");
        assertThat(charizardItem.daysUntilStockout()).isEqualTo(4);
        assertThat(charizardItem.reorderPoint()).isEqualTo(10);
        assertThat(charizardItem.suggestedRestockQty()).isEqualTo(26);
        assertThat(charizardItem.urgencyStatus()).isEqualTo("CRITICAL");

        StockForecastResponse.Item mewtwoItem = response.items().get(3);
        assertThat(mewtwoItem.daysUntilStockout()).isNull();
        assertThat(mewtwoItem.estimatedStockoutDate()).isNull();
        assertThat(mewtwoItem.urgencyStatus()).isEqualTo("NO_RECENT_SALES");
    }

    @Test
    void rejectsInvalidParametersAndNonSellers() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(seller));

        assertThatThrownBy(() -> service.getForecast(10L, 15, "WEIGHTED", 7, 3, 30, false, null, null))
            .isInstanceOf(SellerProductValidationException.class)
            .hasMessageContaining("7, 14, 30, 60, or 90");

        assertThatThrownBy(() -> service.getForecast(10L, 30, "WEIGHTED", -1, 3, 30, false, null, null))
            .isInstanceOf(SellerProductValidationException.class)
            .hasMessageContaining("Lead time");

        assertThatThrownBy(() -> service.getForecast(10L, 30, "INVALID", 7, 3, 30, false, null, null))
            .isInstanceOf(SellerProductValidationException.class)
            .hasMessageContaining("WEIGHTED or SIMPLE");

        User nonSeller = User.builder().id(99L).sellerStatus("NONE").build();
        when(userRepository.findById(99L)).thenReturn(Optional.of(nonSeller));
        assertThatThrownBy(() -> service.getForecast(99L, 30, "WEIGHTED", 7, 3, 30, false, null, null))
            .isInstanceOf(SellerRequiredException.class);
    }

    private Product product(Long id, String name, int onHand, int reserved, int auctionReserved, String status) {
        return Product.builder()
            .id(id)
            .seller(seller)
            .card(Card.builder().id("card-" + id).name(name).supertype("Pokémon").setName("Base Set").rarity("Rare").build())
            .customImageUrl("https://example.com/" + id + ".png")
            .condition("NM")
            .language("EN")
            .price(BigDecimal.valueOf(500))
            .onHand(onHand)
            .reserved(reserved)
            .auctionReserved(auctionReserved)
            .status(status)
            .build();
    }

    private ProductSalesTotal salesRow(Long productId, Long unitsSold) {
        return new ProductSalesTotal() {
            @Override
            public Long getProductId() {
                return productId;
            }

            @Override
            public Long getUnitsSold() {
                return unitsSold;
            }
        };
    }
}

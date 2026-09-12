package com.mythix.tcg.cart.application;

import com.mythix.tcg.cart.api.AddCartItemRequest;
import com.mythix.tcg.cart.api.CartResponse;
import com.mythix.tcg.cart.domain.CartItem;
import com.mythix.tcg.cart.persistence.CartItemRepository;
import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceTests {

    @Mock
    private CartItemRepository cartItemRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private UserRepository userRepository;

    private CartService service;
    private User buyer;
    private Product product;

    @BeforeEach
    void setUp() {
        service = new CartService(cartItemRepository, productRepository, userRepository);
        buyer = User.builder().id(7L).email("buyer@example.com").build();
        User seller = User.builder().id(8L).email("seller@example.com").build();
        Card card = Card.builder()
            .id("card-1")
            .name("Pikachu")
            .supertype("Pokemon")
            .imageLarge("https://example.com/pikachu.webp")
            .build();
        product = Product.builder()
            .id(42L)
            .seller(seller)
            .card(card)
            .condition("Near Mint")
            .price(new BigDecimal("12.50"))
            .cost(new BigDecimal("4.00"))
            .onHand(5)
            .reserved(1)
            .status("SHOW")
            .build();
    }

    @Test
    void addIncrementsExistingLineWithoutChangingStock() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(buyer));
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(buyer));
        CartItem existing = CartItem.builder().id(1L).user(buyer).product(product).quantity(1).build();
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        when(cartItemRepository.findByUserIdAndProductIdWithProduct(7L, 42L))
            .thenReturn(Optional.of(existing));
        when(cartItemRepository.findAllByUserIdWithProduct(7L)).thenReturn(List.of(existing));

        CartResponse response = service.addItem(7L, new AddCartItemRequest("42", 2));

        ArgumentCaptor<CartItem> saved = ArgumentCaptor.forClass(CartItem.class);
        verify(cartItemRepository).save(saved.capture());
        assertThat(saved.getValue().getQuantity()).isEqualTo(3);
        assertThat(product.getOnHand()).isEqualTo(5);
        assertThat(product.getReserved()).isEqualTo(1);
        assertThat(response.totalItems()).isEqualTo(3);
        assertThat(response.totalAmount()).isEqualByComparingTo("37.50");
    }

    @Test
    void addRejectsQuantityAboveAvailableStock() {
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(buyer));
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        when(cartItemRepository.findByUserIdAndProductIdWithProduct(7L, 42L))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addItem(7L, new AddCartItemRequest("42", 5)))
            .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void getCartMarksStaleLineUnavailableAndKeepsQuantity() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(buyer));
        CartItem existing = CartItem.builder().id(1L).user(buyer).product(product).quantity(5).build();
        product.setOnHand(2);
        when(cartItemRepository.findAllByUserIdWithProduct(7L)).thenReturn(List.of(existing));

        CartResponse response = service.getCart(7L);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).quantity()).isEqualTo(5);
        assertThat(response.items().get(0).available()).isFalse();
        assertThat(response.items().get(0).availableQuantity()).isEqualTo(1);
    }
}

package com.mythix.tcg.order.api;

import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.cart.domain.CartItem;
import com.mythix.tcg.cart.persistence.CartItemRepository;
import com.mythix.tcg.order.application.CheckoutService;
import com.mythix.tcg.order.application.CheckoutInsufficientStockException;
import com.mythix.tcg.order.api.CheckoutDto.CheckoutResponse;
import com.mythix.tcg.order.persistence.OrderItemRepository;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL-only proof that pessimistic product locks prevent overselling. */
@SpringBootTest
@ActiveProfiles("postgres")
@EnabledIfSystemProperty(named = "spring.profiles.active", matches = ".*postgres.*")
class CheckoutConcurrencyPostgresTests {
    @Autowired private CheckoutService checkoutService;
    @Autowired private CartItemRepository cartItemRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private com.mythix.tcg.catalog.persistence.CardRepository cardRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private Long firstBuyerId;
    private Long secondBuyerId;
    private Long productId;
    private Long secondProductId;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            orderItemRepository.deleteAll();
            orderRepository.deleteAll();
            cartItemRepository.deleteAll();
            productRepository.deleteAll();
            userRepository.deleteAll();
            cardRepository.deleteAll();

            User firstBuyer = userRepository.save(User.builder()
                .email("concurrency-a-" + UUID.randomUUID() + "@example.com")
                .password("hash").fullName("Buyer A").role("USER").sellerStatus("NONE").build());
            User secondBuyer = userRepository.save(User.builder()
                .email("concurrency-b-" + UUID.randomUUID() + "@example.com")
                .password("hash").fullName("Buyer B").role("USER").sellerStatus("NONE").build());
            User seller = userRepository.save(User.builder()
                .email("concurrency-seller-" + UUID.randomUUID() + "@example.com")
                .password("hash").fullName("Seller").role("SELLER").sellerStatus("APPROVED").build());
            Card card = cardRepository.save(Card.builder()
                .id("concurrency-card-" + UUID.randomUUID()).name("Rare Card").build());
            Product product = productRepository.save(Product.builder()
                .seller(seller).card(card).condition("Near Mint").language("EN")
                .price(new BigDecimal("10.00")).cost(new BigDecimal("4.00"))
                .onHand(1).reserved(0).status("SHOW").build());
            Card secondCard = cardRepository.save(Card.builder()
                .id("concurrency-card-" + UUID.randomUUID()).name("Second Rare Card").build());
            Product secondProduct = productRepository.save(Product.builder()
                .seller(seller).card(secondCard).condition("Near Mint").language("EN")
                .price(new BigDecimal("11.00")).cost(new BigDecimal("4.50"))
                .onHand(1).reserved(0).status("SHOW").build());
            // Deliberately insert the second buyer's cart in reverse order. The service
            // must still lock products in ascending ID order to avoid deadlocks.
            cartItemRepository.save(CartItem.builder().user(firstBuyer).product(product).quantity(1).build());
            cartItemRepository.save(CartItem.builder().user(firstBuyer).product(secondProduct).quantity(1).build());
            cartItemRepository.save(CartItem.builder().user(secondBuyer).product(secondProduct).quantity(1).build());
            cartItemRepository.save(CartItem.builder().user(secondBuyer).product(product).quantity(1).build());
            firstBuyerId = firstBuyer.getId();
            secondBuyerId = secondBuyer.getId();
            productId = product.getId();
            secondProductId = secondProduct.getId();
        });
    }

    @Test
    void concurrentBuyersCanReserveOnlyTheAvailableStock() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> checkout(firstBuyerId, ready, start));
            Future<Object> second = executor.submit(() -> checkout(secondBuyerId, ready, start));
            ready.await();
            start.countDown();
            List<Object> results = List.of(first.get(), second.get());

            assertThat(results).filteredOn(result -> result instanceof CheckoutResponse).hasSize(1);
            assertThat(results).filteredOn(result -> result instanceof CheckoutInsufficientStockException)
                .hasSize(1);
        } finally {
            executor.shutdownNow();
        }

        Product product = productRepository.findById(productId).orElseThrow();
        assertThat(product.getReserved()).isEqualTo(1);
        assertThat(product.getReserved()).isLessThanOrEqualTo(product.getOnHand());
        Product secondProduct = productRepository.findById(secondProductId).orElseThrow();
        assertThat(secondProduct.getReserved()).isEqualTo(1);
        assertThat(secondProduct.getReserved()).isLessThanOrEqualTo(secondProduct.getOnHand());
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    private Object checkout(Long buyerId, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
            return checkoutService.checkout(buyerId, UUID.randomUUID().toString());
        } catch (Exception exception) {
            return exception;
        }
    }
}

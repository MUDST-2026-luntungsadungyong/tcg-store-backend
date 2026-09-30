package com.mythix.tcg.simulation;

import com.mythix.tcg.admin.persistence.AuditLogRepository;
import com.mythix.tcg.cart.api.AddCartItemRequest;
import com.mythix.tcg.cart.application.CartService;
import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.collection.api.CollectionDto.AddToCollectionRequest;
import com.mythix.tcg.collection.application.CollectionService;
import com.mythix.tcg.collection.persistence.CollectionRepository;
import com.mythix.tcg.order.application.CheckoutService;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.domain.OrderItem;
import com.mythix.tcg.order.persistence.OrderItemRepository;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.notification.persistence.NotificationRepository;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.seller.domain.SellerProfile;
import com.mythix.tcg.seller.persistence.SellerProfileRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A deterministic end-to-end data simulation. It deliberately uses application services
 * instead of repositories for cart, checkout, and collection mutations so transaction,
 * validation, stock reservation, and collection upsert behavior are exercised together.
 */
@SpringBootTest
@ActiveProfiles("test")
class MarketplaceSimulationIntegrationTests {

    private static final int SELLER_COUNT = 50;
    private static final int BUYER_COUNT = 50;
    private static final int CARD_COUNT = 12;
    private static final long RANDOM_SEED = 20260925L;

    @Autowired
    private CartService cartService;

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private CollectionService collectionService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private CollectionRepository collectionRepository;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private SellerProfileRepository sellerProfileRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void cleanDatabase() {
        auditLogRepository.deleteAll();
        notificationRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
        collectionRepository.deleteAll();
        sellerProfileRepository.deleteAll();
        userRepository.deleteAll();
        cardRepository.deleteAll();
    }

    @AfterEach
    void cleanSimulationData() {
        cleanDatabase();
    }

    @Test
    void runsFiftySellersAndFiftyBuyersThroughRandomMarketplaceBehaviors() {
        String runId = UUID.randomUUID().toString();
        SplittableRandom random = new SplittableRandom(RANDOM_SEED);

        List<Card> cards = seedCanonicalCards(runId);
        List<User> sellers = seedApprovedSellers(runId);
        List<User> buyers = seedBuyers(runId);
        List<Product> listings = seedListings(sellers, cards, random);

        int collectionEvents = 0;
        int cartEvents = 0;
        int checkoutEvents = 0;

        for (User buyer : buyers) {
            int collectionAttempts = 1 + random.nextInt(3);
            for (int event = 0; event < collectionAttempts; event++) {
                Card card = cards.get(random.nextInt(cards.size()));
                collectionService.addToCollection(buyer.getId(), AddToCollectionRequest.builder()
                    .cardId(card.getId())
                    .condition(randomCondition(random))
                    .language(randomLanguage(random))
                    .quantity(1 + random.nextInt(3))
                    .build());
                collectionEvents++;
            }

            int cartAttempts = random.nextInt(3);
            for (int event = 0; event < cartAttempts; event++) {
                Product listing = listings.get(random.nextInt(listings.size()));
                cartService.addItem(buyer.getId(), new AddCartItemRequest(
                    String.valueOf(listing.getId()),
                    1 + random.nextInt(2)));
                cartEvents++;
            }

            if (cartAttempts > 0) {
                checkoutService.checkout(buyer.getId(), "simulation-" + runId + "-" + buyer.getId());
                checkoutEvents++;
            }
        }

        assertThat(userRepository.count()).isEqualTo(SELLER_COUNT + BUYER_COUNT);
        assertThat(userRepository.countBySellerStatus("APPROVED")).isEqualTo(SELLER_COUNT);
        assertThat(sellerProfileRepository.count()).isEqualTo(SELLER_COUNT);
        assertThat(cardRepository.count()).isEqualTo(CARD_COUNT);
        assertThat(productRepository.count()).isEqualTo(SELLER_COUNT);
        assertThat(collectionRepository.count())
            .isPositive()
            .isLessThanOrEqualTo(collectionEvents);
        assertThat(orderRepository.count()).isEqualTo(checkoutEvents);
        assertThat(orderRepository.count()).isGreaterThan(0);
        assertThat(cartEvents).isGreaterThan(0);
        assertThat(collectionEvents).isGreaterThan(0);

        List<Order> orders = orderRepository.findAll();
        assertThat(orders).allSatisfy(order -> {
            assertThat(order.getBuyer()).isNotNull();
            assertThat(order.getTotalAmount()).isGreaterThan(BigDecimal.ZERO);
        });

        List<OrderItem> orderItems = orderItemRepository.findAll();
        assertThat(orderItems).isNotEmpty().allSatisfy(item -> {
            assertThat(item.getSeller()).isNotNull();
            assertThat(item.getProduct()).isNotNull();
            assertThat(item.getCardId()).isNotBlank();
            assertThat(item.getProductNameSnapshot()).isNotBlank();
        });

        assertThat(productRepository.findAll())
            .allSatisfy(listing -> assertThat(listing.getReserved()).isGreaterThanOrEqualTo(0));
    }

    private List<Card> seedCanonicalCards(String runId) {
        List<Card> cards = new ArrayList<>();
        for (int index = 0; index < CARD_COUNT; index++) {
            cards.add(Card.builder()
                .id("simulation-card-" + runId + "-" + index)
                .name("Simulation Card " + index)
                .supertype("Pokemon")
                .setName("Simulation Set")
                .cardNumber(String.format("%03d", index + 1))
                .rarity(index % 3 == 0 ? "Rare" : "Common")
                .imageSmall("https://example.com/simulation/" + index + "-small.webp")
                .imageLarge("https://example.com/simulation/" + index + ".webp")
                .marketPrice(BigDecimal.valueOf(10L + index))
                .active(true)
                .build());
        }
        return cardRepository.saveAll(cards);
    }

    private List<User> seedApprovedSellers(String runId) {
        List<User> sellers = new ArrayList<>();
        for (int index = 0; index < SELLER_COUNT; index++) {
            sellers.add(User.builder()
                .email("simulation-seller-" + index + "-" + runId + "@example.com")
                .password("simulation-password-hash")
                .fullName("Simulation Seller " + index)
                .role("USER")
                .sellerStatus("APPROVED")
                .build());
        }
        List<User> savedSellers = userRepository.saveAll(sellers);
        List<SellerProfile> profiles = savedSellers.stream()
            .map(seller -> SellerProfile.builder()
                .user(seller)
                .shopName(seller.getFullName() + " Shop")
                .shopDescription("Deterministic integration-test seller")
                .requestedAt(LocalDateTime.now())
                .verifiedAt(LocalDateTime.now())
                .build())
            .toList();
        sellerProfileRepository.saveAll(profiles);
        return savedSellers;
    }

    private List<User> seedBuyers(String runId) {
        List<User> buyers = new ArrayList<>();
        for (int index = 0; index < BUYER_COUNT; index++) {
            buyers.add(User.builder()
                .email("simulation-buyer-" + index + "-" + runId + "@example.com")
                .password("simulation-password-hash")
                .fullName("Simulation Buyer " + index)
                .role("USER")
                .sellerStatus("NONE")
                .build());
        }
        return userRepository.saveAll(buyers);
    }

    private List<Product> seedListings(List<User> sellers, List<Card> cards, SplittableRandom random) {
        List<Product> listings = new ArrayList<>();
        for (int index = 0; index < sellers.size(); index++) {
            Card card = cards.get(index % cards.size());
            String image = card.getImageLarge();
            listings.add(productRepository.save(Product.builder()
                .seller(sellers.get(index))
                .card(card)
                .customImageUrl(image)
                .condition(randomCondition(random))
                .language(randomLanguage(random))
                .price(BigDecimal.valueOf(20L + random.nextInt(480)))
                .cost(BigDecimal.valueOf(10L + random.nextInt(100)))
                .onHand(25)
                .reserved(0)
                .status("SHOW")
                .build()));
        }
        return listings;
    }

    private String randomCondition(SplittableRandom random) {
        return List.of("NM", "LP", "MP").get(random.nextInt(3));
    }

    private String randomLanguage(SplittableRandom random) {
        return List.of("EN", "JP", "TH").get(random.nextInt(3));
    }
}

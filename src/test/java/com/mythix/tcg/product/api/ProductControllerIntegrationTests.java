package com.mythix.tcg.product.api;

import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.cart.persistence.CartItemRepository;
import com.mythix.tcg.order.persistence.OrderItemRepository;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.seller.domain.SellerProfile;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductControllerIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CartItemRepository cartItemRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.mythix.tcg.seller.persistence.SellerProfileRepository sellerProfileRepository;

    @Autowired
    private com.mythix.tcg.collection.persistence.CollectionRepository collectionRepository;

    @Autowired
    private com.mythix.tcg.notification.persistence.NotificationRepository notificationRepository;

    private User seller;
    private Card pikachuCard;
    private Card charizardCard;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        collectionRepository.deleteAll();
        cartItemRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
        sellerProfileRepository.deleteAll();
        userRepository.deleteAll();
        cardRepository.deleteAll();

        seller = userRepository.save(User.builder()
                .email("seller@test.com")
                .password("hashed_pass")
                .fullName("Ash Vault")
                .role("USER")
                .build());

        pikachuCard = cardRepository.save(Card.builder()
                .id("test-pika-1")
                .name("Pikachu Illustrator")
                .supertype("Pokémon")
                .subtypes("Basic")
                .rarity("Rare Holo")
                .setName("Promotional Cards")
                .cardNumber("001")
                .marketPrice(new BigDecimal("1500.00"))
                .imageSmall("https://images.com/small.png")
                .imageLarge("https://images.com/large.png")
                .build());

        charizardCard = cardRepository.save(Card.builder()
                .id("test-char-1")
                .name("Charizard VMAX")
                .supertype("Pokémon")
                .subtypes("VMAX")
                .rarity("Ultra Rare")
                .setName("Darkness Ablaze")
                .cardNumber("020")
                .marketPrice(new BigDecimal("350.00"))
                .imageSmall("https://images.com/char-small.png")
                .imageLarge("https://images.com/char.png")
                .build());
    }

    @Test
    void browseProducts_ShouldReturnOnlyShownProductsWithoutAuth() throws Exception {
        productRepository.save(Product.builder()
                .seller(seller)
                .card(pikachuCard)
                .price(new BigDecimal("1200.00"))
                .condition("Near Mint")
                .status("SHOW")
                .onHand(5)
                .reserved(1)
                .build());

        productRepository.save(Product.builder()
                .seller(seller)
                .card(charizardCard)
                .price(new BigDecimal("300.00"))
                .condition("Mint")
                .status("HIDE")
                .onHand(2)
                .reserved(0)
                .build());

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name").value("Pikachu Illustrator"))
                .andExpect(jsonPath("$.items[0].price").value(1200.00))
                .andExpect(jsonPath("$.items[0].stock").value(4))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void browseProducts_ShouldFilterByNameAndPriceRange() throws Exception {
        productRepository.save(Product.builder()
                .seller(seller)
                .card(pikachuCard)
                .price(new BigDecimal("1200.00"))
                .condition("Near Mint")
                .status("SHOW")
                .onHand(3)
                .reserved(0)
                .build());

        productRepository.save(Product.builder()
                .seller(seller)
                .card(charizardCard)
                .price(new BigDecimal("300.00"))
                .condition("Mint")
                .status("SHOW")
                .onHand(2)
                .reserved(0)
                .build());

        // Search for Pikachu
        mockMvc.perform(get("/api/products")
                        .param("search", "pikachu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name").value("Pikachu Illustrator"));

        // Filter by price range: min 200, max 500
        mockMvc.perform(get("/api/products")
                        .param("minPrice", "200")
                        .param("maxPrice", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name").value("Charizard VMAX"));
    }

    @Test
    void browseProducts_ShouldFilterByCardAndSellerAndExposeStoreIdentity() throws Exception {
        sellerProfileRepository.save(SellerProfile.builder()
                .user(seller)
                .shopName("Ash Vault")
                .build());

        productRepository.save(Product.builder()
                .seller(seller)
                .card(pikachuCard)
                .price(new BigDecimal("1200.00"))
                .condition("Near Mint")
                .status("SHOW")
                .onHand(3)
                .reserved(0)
                .build());

        productRepository.save(Product.builder()
                .seller(seller)
                .card(charizardCard)
                .price(new BigDecimal("300.00"))
                .condition("Mint")
                .status("SHOW")
                .onHand(2)
                .reserved(0)
                .build());

        mockMvc.perform(get("/api/products")
                        .param("cardId", pikachuCard.getId())
                        .param("sellerId", String.valueOf(seller.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].cardId").value(pikachuCard.getId()))
                .andExpect(jsonPath("$.items[0].sellerId").value(String.valueOf(seller.getId())))
                .andExpect(jsonPath("$.items[0].sellerName").value("Ash Vault"));
    }

    @Test
    void getProductById_ShouldReturnProduct_WhenExistsAndShown() throws Exception {
        Product p = productRepository.save(Product.builder()
                .seller(seller)
                .card(pikachuCard)
                .price(new BigDecimal("1200.00"))
                .condition("Near Mint")
                .status("SHOW")
                .onHand(5)
                .reserved(0)
                .build());

        mockMvc.perform(get("/api/products/{id}", p.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(String.valueOf(p.getId())))
                .andExpect(jsonPath("$.name").value("Pikachu Illustrator"))
                .andExpect(jsonPath("$.price").value(1200.00));
    }

    @Test
    void getProductById_ShouldReturn404_WhenHiddenOrNotFound() throws Exception {
        Product hidden = productRepository.save(Product.builder()
                .seller(seller)
                .card(pikachuCard)
                .price(new BigDecimal("1200.00"))
                .condition("Near Mint")
                .status("HIDE")
                .onHand(5)
                .reserved(0)
                .build());

        mockMvc.perform(get("/api/products/{id}", hidden.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(get("/api/products/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}

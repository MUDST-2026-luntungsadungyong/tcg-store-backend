package com.mythix.tcg.collection.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mythix.tcg.auth.api.RegisterRequest;
import com.mythix.tcg.auth.application.AuthService;
import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.collection.api.CollectionDto.AddToCollectionRequest;
import com.mythix.tcg.collection.domain.CollectionItem;
import com.mythix.tcg.collection.persistence.CollectionRepository;
import com.mythix.tcg.order.persistence.OrderItemRepository;
import com.mythix.tcg.order.persistence.OrderRepository;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.seller.persistence.SellerProfileRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CollectionControllerIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private CollectionRepository collectionRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private SellerProfileRepository sellerProfileRepository;

    private String user1Token;
    private User user1;
    private String user2Token;
    private User user2;
    private Card card;

    @BeforeEach
    void setUp() {
        collectionRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
        sellerProfileRepository.deleteAll();
        userRepository.deleteAll();
        cardRepository.deleteAll();

        // 1. Create User 1
        var auth1 = authService.register(new RegisterRequest("User One", "user1@example.com", "password123"));
        user1Token = auth1.token();
        user1 = userRepository.findById(Long.valueOf(auth1.user().id())).orElseThrow();

        // 2. Create User 2
        var auth2 = authService.register(new RegisterRequest("User Two", "user2@example.com", "password123"));
        user2Token = auth2.token();
        user2 = userRepository.findById(Long.valueOf(auth2.user().id())).orElseThrow();

        // 3. Create Card
        card = Card.builder()
                .id("card-pikachu")
                .name("Pikachu")
                .setName("Jungle")
                .cardNumber("60/64")
                .rarity("Common")
                .imageSmall("https://example.com/pika_small.png")
                .imageLarge("https://example.com/pika_large.png")
                .build();
        cardRepository.save(card);
    }

    @Test
    @DisplayName("GET /api/collection should return 401 when unauthenticated")
    void getCollection_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/collection"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/collection should return collection only for the authenticated user")
    void getCollection_authenticated_returnsOnlyUserItems() throws Exception {
        // user1 item
        CollectionItem item1 = CollectionItem.builder()
                .user(user1)
                .card(card)
                .condition("Near Mint")
                .language("EN")
                .quantity(1)
                .build();
        collectionRepository.save(item1);

        // user2 item
        CollectionItem item2 = CollectionItem.builder()
                .user(user2)
                .card(card)
                .condition("Mint")
                .language("JP")
                .quantity(3)
                .build();
        collectionRepository.save(item2);

        // user1 calls GET /api/collection
        mockMvc.perform(get("/api/collection")
                        .header("Authorization", "Bearer " + user1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].cardName").value("Pikachu"))
                .andExpect(jsonPath("$[0].quantity").value(1))
                .andExpect(jsonPath("$[0].listing.name").value("Pikachu"));
    }

    @Test
    @DisplayName("POST /api/collection should add card to user collection and return 201")
    void addToCollection_valid_returns201() throws Exception {
        AddToCollectionRequest request = AddToCollectionRequest.builder()
                .cardId("card-pikachu")
                .condition("Near Mint")
                .language("EN")
                .quantity(2)
                .build();

        mockMvc.perform(post("/api/collection")
                        .header("Authorization", "Bearer " + user1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cardId").value("card-pikachu"))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.listing.name").value("Pikachu"));
    }

    @Test
    @DisplayName("POST /api/collection should return 404 when cardId does not exist")
    void addToCollection_cardNotFound_returns404() throws Exception {
        AddToCollectionRequest request = AddToCollectionRequest.builder()
                .cardId("card-non-existent")
                .quantity(1)
                .build();

        mockMvc.perform(post("/api/collection")
                        .header("Authorization", "Bearer " + user1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CARD_NOT_FOUND"));
    }

    @Test
    @DisplayName("DELETE /api/collection/{id} should delete owned item and return 204")
    void removeFromCollection_ownedItem_returns204() throws Exception {
        CollectionItem item = CollectionItem.builder()
                .user(user1)
                .card(card)
                .condition("Near Mint")
                .language("EN")
                .quantity(1)
                .build();
        CollectionItem saved = collectionRepository.save(item);

        mockMvc.perform(delete("/api/collection/" + saved.getId())
                        .header("Authorization", "Bearer " + user1Token))
                .andExpect(status().isNoContent());

        // verify deleted
        mockMvc.perform(get("/api/collection")
                        .header("Authorization", "Bearer " + user1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("DELETE /api/collection/{id} should return 404 when item belongs to another user")
    void removeFromCollection_otherUserItem_returns404() throws Exception {
        CollectionItem itemOfUser2 = CollectionItem.builder()
                .user(user2)
                .card(card)
                .condition("Near Mint")
                .language("EN")
                .quantity(1)
                .build();
        CollectionItem saved = collectionRepository.save(itemOfUser2);

        // user1 tries to delete user2's item
        mockMvc.perform(delete("/api/collection/" + saved.getId())
                        .header("Authorization", "Bearer " + user1Token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COLLECTION_ITEM_NOT_FOUND"));
    }
}

package com.mythix.tcg.collection.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.collection.domain.CollectionItem;
import com.mythix.tcg.collection.persistence.CollectionRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicCollectionControllerIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private CollectionRepository collectionRepository;

    @BeforeEach
    void setUp() {
        collectionRepository.deleteAll();
        userRepository.deleteAll();
        cardRepository.deleteAll();
    }

    @Test
    void leaderboardRanksByRareQuantityAndDoesNotExposePrivateFields() throws Exception {
        User first = saveUser("First Collector", "first@example.com");
        User second = saveUser("Second Collector", "second@example.com");
        Card rare = saveCard("rare-card", "Rare");
        Card promo = saveCard("promo-card", "Promo");
        Card common = saveCard("common-card", "Common");

        saveItem(first, rare, 2);
        saveItem(first, promo, 1);
        saveItem(first, common, 50);
        saveItem(second, rare, 4);

        mockMvc.perform(get("/api/collections/leaderboard")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].displayName").value("Second Collector"))
                .andExpect(jsonPath("$.items[0].rareCardCount").value(4))
                .andExpect(jsonPath("$.items[0].distinctRareCardCount").value(1))
                .andExpect(jsonPath("$.items[1].rareCardCount").value(3))
                .andExpect(jsonPath("$.items[1].highlights", hasSize(2)))
                .andExpect(jsonPath("$.items[0].email").doesNotExist());
    }

    @Test
    void leaderboardReturnsTopTenMockCollectorsOrderedByRareQuantity() throws Exception {
        Card rare = saveCard("top-ten-rare", "Rare Holo");
        Card common = saveCard("top-ten-common", "Common");

        for (int quantity = 1; quantity <= 12; quantity++) {
            User collector = saveUser(
                    String.format("Mock Collector %02d", quantity),
                    String.format("mock-collector-%02d@example.com", quantity));
            saveItem(collector, rare, quantity);
            if (quantity == 12) {
                // A large common-card quantity must not affect the rare-card ranking.
                saveItem(collector, common, 999);
            }
        }

        mockMvc.perform(get("/api/collections/leaderboard")
                        .param("limit", "10")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(10)))
                .andExpect(jsonPath("$.items[0].rank").value(1))
                .andExpect(jsonPath("$.items[0].displayName").value("Mock Collector 12"))
                .andExpect(jsonPath("$.items[0].rareCardCount").value(12))
                .andExpect(jsonPath("$.items[0].distinctRareCardCount").value(1))
                .andExpect(jsonPath("$.items[0].highlights", hasSize(1)))
                .andExpect(jsonPath("$.items[9].rank").value(10))
                .andExpect(jsonPath("$.items[9].displayName").value("Mock Collector 03"))
                .andExpect(jsonPath("$.items[9].rareCardCount").value(3));
    }

    @Test
    void publicCollectionIsReadableWithoutAuthentication() throws Exception {
        User owner = saveUser("Public Collector", "public@example.com");
        Card rare = saveCard("public-rare", "Rare Holo");
        saveItem(owner, rare, 2);

        mockMvc.perform(get("/api/collections/" + owner.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner.userId").value(String.valueOf(owner.getId())))
                .andExpect(jsonPath("$.owner.displayName").value("Public Collector"))
                .andExpect(jsonPath("$.owner.rareCardCount").value(2))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.owner.email").doesNotExist());
    }

    @Test
    void missingPublicCollectionUserReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/collections/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PUBLIC_COLLECTION_NOT_FOUND"));
    }

    private User saveUser(String name, String email) {
        return userRepository.save(User.builder()
                .fullName(name)
                .email(email)
                .password("not-used")
                .role("USER")
                .sellerStatus("NONE")
                .build());
    }

    private Card saveCard(String id, String rarity) {
        return cardRepository.save(Card.builder()
                .id(id)
                .name(id)
                .rarity(rarity)
                .imageSmall("https://example.test/" + id + ".png")
                .build());
    }

    private void saveItem(User user, Card card, int quantity) {
        collectionRepository.save(CollectionItem.builder()
                .user(user)
                .card(card)
                .condition("Near Mint")
                .language("EN")
                .quantity(quantity)
                .build());
    }
}

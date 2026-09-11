package com.mythix.tcg.collection.application;

import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.collection.api.CollectionDto.AddToCollectionRequest;
import com.mythix.tcg.collection.api.CollectionDto.CollectionItemResponse;
import com.mythix.tcg.collection.domain.CollectionItem;
import com.mythix.tcg.collection.persistence.CollectionRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CollectionServiceTest {

    @Mock
    private CollectionRepository collectionRepository;

    @Mock
    private CardRepository cardRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CollectionService collectionService;

    private User user;
    private Card card;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(1L)
                .email("test@example.com")
                .fullName("Test User")
                .build();

        card = Card.builder()
                .id("card-100")
                .name("Charizard")
                .setName("Base Set")
                .cardNumber("4/102")
                .rarity("Rare Holo")
                .imageSmall("https://example.com/small.png")
                .imageLarge("https://example.com/large.png")
                .build();
    }

    @Test
    @DisplayName("getUserCollection should return list of mapped items for the user")
    void getUserCollection_returnsMappedResponses() {
        CollectionItem item = CollectionItem.builder()
                .id(10L)
                .user(user)
                .card(card)
                .condition("Near Mint")
                .language("EN")
                .quantity(2)
                .createdAt(LocalDateTime.now())
                .build();

        when(collectionRepository.findByUserIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(item));

        List<CollectionItemResponse> result = collectionService.getUserCollection(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("10");
        assertThat(result.get(0).getCardName()).isEqualTo("Charizard");
        assertThat(result.get(0).getQuantity()).isEqualTo(2);
        assertThat(result.get(0).getListing().getName()).isEqualTo("Charizard");
    }

    @Test
    @DisplayName("addToCollection should create a new item when card not in user collection")
    void addToCollection_newCard_savesAndReturns() {
        AddToCollectionRequest request = AddToCollectionRequest.builder()
                .cardId("card-100")
                .condition("Near Mint")
                .language("EN")
                .quantity(1)
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(cardRepository.findById("card-100")).thenReturn(Optional.of(card));
        when(collectionRepository.findByUserIdAndCardId(1L, "card-100")).thenReturn(Optional.empty());

        CollectionItem savedItem = CollectionItem.builder()
                .id(11L)
                .user(user)
                .card(card)
                .condition("Near Mint")
                .language("EN")
                .quantity(1)
                .createdAt(LocalDateTime.now())
                .build();

        when(collectionRepository.save(any(CollectionItem.class))).thenReturn(savedItem);

        CollectionItemResponse response = collectionService.addToCollection(1L, request);

        assertThat(response.getId()).isEqualTo("11");
        assertThat(response.getCardId()).isEqualTo("card-100");
        assertThat(response.getQuantity()).isEqualTo(1);
        verify(collectionRepository).save(any(CollectionItem.class));
    }

    @Test
    @DisplayName("addToCollection should increment quantity when card already in user collection")
    void addToCollection_existingCard_incrementsQuantity() {
        AddToCollectionRequest request = AddToCollectionRequest.builder()
                .cardId("card-100")
                .quantity(2)
                .build();

        CollectionItem existingItem = CollectionItem.builder()
                .id(12L)
                .user(user)
                .card(card)
                .condition("Near Mint")
                .language("EN")
                .quantity(1)
                .createdAt(LocalDateTime.now())
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(cardRepository.findById("card-100")).thenReturn(Optional.of(card));
        when(collectionRepository.findByUserIdAndCardId(1L, "card-100")).thenReturn(Optional.of(existingItem));
        when(collectionRepository.save(existingItem)).thenReturn(existingItem);

        CollectionItemResponse response = collectionService.addToCollection(1L, request);

        assertThat(existingItem.getQuantity()).isEqualTo(3);
        verify(collectionRepository).save(existingItem);
    }

    @Test
    @DisplayName("addToCollection should throw CardNotFoundException when card not in catalog")
    void addToCollection_cardNotFound_throwsCardNotFoundException() {
        AddToCollectionRequest request = AddToCollectionRequest.builder()
                .cardId("unknown-card")
                .quantity(1)
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(cardRepository.findById("unknown-card")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> collectionService.addToCollection(1L, request))
                .isInstanceOf(CardNotFoundException.class)
                .hasMessageContaining("unknown-card");

        verify(collectionRepository, never()).save(any());
    }

    @Test
    @DisplayName("removeFromCollection should delete item when item belongs to user")
    void removeFromCollection_happyPath_deletesItem() {
        CollectionItem item = CollectionItem.builder()
                .id(20L)
                .user(user)
                .card(card)
                .build();

        when(collectionRepository.findByIdAndUserId(20L, 1L)).thenReturn(Optional.of(item));

        collectionService.removeFromCollection(1L, 20L);

        verify(collectionRepository).delete(item);
    }

    @Test
    @DisplayName("removeFromCollection should throw CollectionItemNotFoundException when item not owned by user")
    void removeFromCollection_unauthorized_throwsException() {
        when(collectionRepository.findByIdAndUserId(20L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> collectionService.removeFromCollection(1L, 20L))
                .isInstanceOf(CollectionItemNotFoundException.class)
                .hasMessageContaining("20");

        verify(collectionRepository, never()).delete(any());
    }
}

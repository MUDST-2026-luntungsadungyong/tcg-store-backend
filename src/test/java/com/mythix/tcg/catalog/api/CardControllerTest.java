package com.mythix.tcg.catalog.api;

import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.catalog.persistence.CardRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Catalog: CardController Unit Tests")
class CardControllerTest {

    @Mock
    private CardRepository cardRepository;

    @InjectMocks
    private CardController controller;

    private Card createSampleCard(String id, String name) {
        return Card.builder()
                .id(id)
                .name(name)
                .supertype("Pokémon")
                .subtypes("VMAX")
                .hp("330")
                .types("Fire")
                .rarity("Secret Rare")
                .setName("Shining Fates")
                .cardNumber("SV107")
                .imageSmall("https://images.pokemontcg.io/swsh45/sv107.png")
                .imageLarge("https://images.pokemontcg.io/swsh45/sv107_hires.png")
                .marketPrice(new BigDecimal("3500.00"))
                .build();
    }

    @Test
    @DisplayName("getCards() - returns paged cards when no search keyword is provided")
    void getCards_noSearch_returnsAllCards() {
        Card card = createSampleCard("swsh45-SV107", "Charizard VMAX");
        Page<Card> pagedCards = new PageImpl<>(List.of(card), PageRequest.of(0, 20), 1);
        when(cardRepository.findAll(any(Pageable.class))).thenReturn(pagedCards);

        ResponseEntity<Page<Card>> response = controller.getCards(0, 20, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(1);
        assertThat(response.getBody().getContent().get(0).getName()).isEqualTo("Charizard VMAX");
        verify(cardRepository).findAll(any(Pageable.class));
    }

    @Test
    @DisplayName("getCards() - filters cards by name when search query is provided")
    void getCards_withSearch_returnsFilteredCards() {
        Card card = createSampleCard("base1-58", "Pikachu");
        Page<Card> pagedCards = new PageImpl<>(List.of(card), PageRequest.of(0, 20), 1);
        when(cardRepository.findByNameContainingIgnoreCase(eq("Pikachu"), any(Pageable.class)))
                .thenReturn(pagedCards);

        ResponseEntity<Page<Card>> response = controller.getCards(0, 20, "  Pikachu  ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(1);
        assertThat(response.getBody().getContent().get(0).getName()).isEqualTo("Pikachu");
        verify(cardRepository).findByNameContainingIgnoreCase(eq("Pikachu"), any(Pageable.class));
    }

    @Test
    @DisplayName("getCards() - treats blank or whitespace search query as findAll")
    void getCards_blankSearch_callsFindAll() {
        Page<Card> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(cardRepository.findAll(any(Pageable.class))).thenReturn(emptyPage);

        ResponseEntity<Page<Card>> response = controller.getCards(0, 20, "   ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(cardRepository).findAll(any(Pageable.class));
    }

    @Test
    @DisplayName("getCardById() - returns 200 OK and card detail when found")
    void getCardById_found_returnsCard() {
        Card card = createSampleCard("crd-1", "Mewtwo GX");
        when(cardRepository.findById("crd-1")).thenReturn(Optional.of(card));

        ResponseEntity<Card> response = controller.getCardById("crd-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isEqualTo("crd-1");
        assertThat(response.getBody().getName()).isEqualTo("Mewtwo GX");
    }

    @Test
    @DisplayName("getCardById() - returns 404 NOT FOUND when card does not exist")
    void getCardById_notFound_returns404() {
        when(cardRepository.findById("non-existent")).thenReturn(Optional.empty());

        ResponseEntity<Card> response = controller.getCardById("non-existent");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }
}

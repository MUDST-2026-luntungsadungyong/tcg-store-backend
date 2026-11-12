package com.mythix.tcg.catalog.application;

import com.mythix.tcg.catalog.domain.FuzzySearchResult;
import com.mythix.tcg.catalog.persistence.CardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Catalog: CardFuzzySearchService Unit Tests")
class CardFuzzySearchServiceTest {

    @Mock
    private CardRepository cardRepository;

    private CardFuzzySearchService fuzzySearchService;

    @BeforeEach
    void setUp() {
        fuzzySearchService = new CardFuzzySearchService(cardRepository);
    }

    @Test
    @DisplayName("levenshteinDistance() - calculates edit distance accurately")
    void levenshteinDistance_computesCorrectDistance() {
        assertThat(CardFuzzySearchService.levenshteinDistance("pokamon", "Pokemon")).isEqualTo(1);
        assertThat(CardFuzzySearchService.levenshteinDistance("chairzard", "Charizard")).isEqualTo(2);
        assertThat(CardFuzzySearchService.levenshteinDistance("pikashu", "Pikachu")).isEqualTo(1);
        assertThat(CardFuzzySearchService.levenshteinDistance("pokemon", "pokemon")).isEqualTo(0);
    }

    @Test
    @DisplayName("findBestSuggestion() - suggests Pokemon when user types pokamon")
    void findBestSuggestion_pokamon_suggestsPokemon() {
        Optional<FuzzySearchResult> result = fuzzySearchService.findBestSuggestion("pokamon");

        assertThat(result).isPresent();
        assertThat(result.get().getSuggestedQuery()).isEqualTo("Pokemon");
        assertThat(result.get().getOriginalQuery()).isEqualTo("pokamon");
        assertThat(result.get().getDistance()).isEqualTo(1);
        assertThat(result.get().isFuzzyMatched()).isTrue();
    }

    @Test
    @DisplayName("findBestSuggestion() - suggests Charizard when user types chairzard")
    void findBestSuggestion_chairzard_suggestsCharizard() {
        Optional<FuzzySearchResult> result = fuzzySearchService.findBestSuggestion("chairzard");

        assertThat(result).isPresent();
        assertThat(result.get().getSuggestedQuery()).isEqualTo("Charizard");
        assertThat(result.get().getOriginalQuery()).isEqualTo("chairzard");
        assertThat(result.get().getDistance()).isEqualTo(2);
    }

    @Test
    @DisplayName("findBestSuggestion() - suggests Pikachu when user types pikashu")
    void findBestSuggestion_pikashu_suggestsPikachu() {
        Optional<FuzzySearchResult> result = fuzzySearchService.findBestSuggestion("pikashu");

        assertThat(result).isPresent();
        assertThat(result.get().getSuggestedQuery()).isEqualTo("Pikachu");
        assertThat(result.get().getDistance()).isEqualTo(1);
    }

    @Test
    @DisplayName("findBestSuggestion() - suggests multi-word corrections like chairzard vmax")
    void findBestSuggestion_multiWord_suggestsCorrection() {
        Optional<FuzzySearchResult> result = fuzzySearchService.findBestSuggestion("chairzard vmax");

        assertThat(result).isPresent();
        assertThat(result.get().getSuggestedQuery()).containsIgnoringCase("Charizard");
    }

    @Test
    @DisplayName("findBestSuggestion() - returns empty when word matches exactly")
    void findBestSuggestion_exactMatch_returnsEmpty() {
        Optional<FuzzySearchResult> result = fuzzySearchService.findBestSuggestion("Pikachu");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findBestSuggestion() - returns empty when edit distance is greater than 2")
    void findBestSuggestion_distanceGreaterThan2_returnsEmpty() {
        Optional<FuzzySearchResult> result = fuzzySearchService.findBestSuggestion("xyz987completelydifferent");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findBestSuggestion() - integrates dynamic card names from repository")
    void findBestSuggestion_withDynamicCardNames() {
        when(cardRepository.findDistinctNames()).thenReturn(List.of("Gyarados EX", "Lugia"));

        Optional<FuzzySearchResult> result = fuzzySearchService.findBestSuggestion("gyarados");

        // Exact match should return empty (no typo)
        assertThat(result).isEmpty();

        // Typo with distance 1
        Optional<FuzzySearchResult> typoResult = fuzzySearchService.findBestSuggestion("gyaradoz");
        assertThat(typoResult).isPresent();
        assertThat(typoResult.get().getSuggestedQuery()).containsIgnoringCase("Gyarados");
    }
}

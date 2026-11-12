package com.mythix.tcg.catalog.api;

import com.mythix.tcg.catalog.application.CardFuzzySearchService;
import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.catalog.domain.CardSearchPage;
import com.mythix.tcg.catalog.domain.FuzzySearchResult;
import com.mythix.tcg.catalog.persistence.CardNameSearch;
import com.mythix.tcg.catalog.persistence.CardRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/cards")
@RequiredArgsConstructor
@Validated
public class CardController {

    private final CardRepository cardRepository;
    private final CardFuzzySearchService fuzzySearchService;

    public ResponseEntity<Page<Card>> getCards(int page, int size, String search) {
        return getCards(page, size, search, false, false);
    }

    @GetMapping
    public ResponseEntity<Page<Card>> getCards(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String search,
            @RequestParam(value = "fuzzy", defaultValue = "false") boolean fuzzy,
            @RequestParam(value = "exact", defaultValue = "false") boolean exact) {

        Pageable pageable = PageRequest.of(page, size);
        String rawSearch = (search != null) ? search.trim() : "";
        String term = CardNameSearch.term(rawSearch);

        if (term.isEmpty()) {
            Page<Card> all = cardRepository.findAll(pageable);
            return ResponseEntity.ok(new CardSearchPage<>(all, null, rawSearch, false));
        }

        // 1. Perform primary search
        Page<Card> result = cardRepository.findAll(
                (card, query, cb) -> CardNameSearch.matches(card, query, cb, term),
                pageable
        );

        // 2. If fuzzy requested or exact match yielded no results (and exact search is not forced)
        if (fuzzy || (!exact && result.isEmpty())) {
            Optional<FuzzySearchResult> suggestionOpt = (fuzzySearchService != null)
                    ? fuzzySearchService.findBestSuggestion(rawSearch)
                    : Optional.empty();

            if (suggestionOpt.isPresent()) {
                FuzzySearchResult suggestion = suggestionOpt.get();
                String suggestedTerm = CardNameSearch.term(suggestion.getSuggestedQuery());
                Page<Card> fuzzyResult = cardRepository.findAll(
                        (card, query, cb) -> CardNameSearch.matches(card, query, cb, suggestedTerm),
                        pageable
                );

                if (!fuzzyResult.isEmpty() || result.isEmpty()) {
                    return ResponseEntity.ok(new CardSearchPage<>(
                            fuzzyResult,
                            suggestion.getSuggestedQuery(),
                            rawSearch,
                            true
                    ));
                }
            }
        }

        return ResponseEntity.ok(new CardSearchPage<>(result, null, rawSearch, false));
    }

    @GetMapping("/search")
    public ResponseEntity<Page<Card>> searchCards(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "fuzzy", defaultValue = "false") boolean fuzzy,
            @RequestParam(value = "exact", defaultValue = "false") boolean exact) {

        String query = (q != null && !q.isBlank()) ? q : search;
        return getCards(page, size, query, fuzzy, exact);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Card> getCardById(@PathVariable String id) {
        return cardRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}

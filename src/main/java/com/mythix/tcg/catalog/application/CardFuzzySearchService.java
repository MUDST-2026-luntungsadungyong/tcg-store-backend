package com.mythix.tcg.catalog.application;

import com.mythix.tcg.catalog.domain.FuzzySearchResult;
import com.mythix.tcg.catalog.persistence.CardRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
public class CardFuzzySearchService {

    private final CardRepository cardRepository;

    private static final List<String> BASE_DICTIONARY = List.of(
            "Pokemon", "Poke Ball", "Charizard", "Pikachu", "Mew", "Mewtwo", "Blastoise", "Venusaur",
            "Bulbasaur", "Charmander", "Squirtle", "Gengar", "Eevee", "Snorlax", "Dragonite", "Lugia",
            "Rayquaza", "Lucario", "Greninja", "Umbreon", "Espeon", "Alakazam", "Machamp", "Gyarados",
            "Zapdos", "Moltres", "Articuno", "Raichu", "Jigglypuff", "Meowth", "Psyduck", "Arcanine",
            "Poliwrath", "Haunter", "Onix", "Lapras", "Ditto", "Vaporeon", "Jolteon", "Flareon",
            "Celebi", "Suicune", "Entei", "Raikou", "Ho-Oh", "Tyranitar", "Gardevoir", "Empoleon",
            "Manaphy", "Paras", "Scyther", "Victreebel", "Tropius", "Regigigas", "Nidoking", "Bellossom",
            "Ivysaur", "Dark Blastoise", "Dark Charizard", "Dark Espeon", "Shining Fates", "VMAX", "VSTAR"
    );

    @Autowired
    public CardFuzzySearchService(@Autowired(required = false) CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    public static int levenshteinDistance(CharSequence s1, CharSequence s2) {
        if (s1 == null || s2 == null) {
            return (s1 == null ? 0 : s1.length()) + (s2 == null ? 0 : s2.length());
        }
        int len1 = s1.length();
        int len2 = s2.length();
        if (len1 == 0) return len2;
        if (len2 == 0) return len1;

        int[] prev = new int[len2 + 1];
        int[] curr = new int[len2 + 1];

        for (int j = 0; j <= len2; j++) {
            prev[j] = j;
        }

        for (int i = 1; i <= len1; i++) {
            curr[0] = i;
            char c1 = Character.toLowerCase(s1.charAt(i - 1));
            for (int j = 1; j <= len2; j++) {
                char c2 = Character.toLowerCase(s2.charAt(j - 1));
                int cost = (c1 == c2) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost
                );
            }
            System.arraycopy(curr, 0, prev, 0, len2 + 1);
        }

        return prev[len2];
    }

    public Optional<FuzzySearchResult> findBestSuggestion(String input) {
        if (input == null || input.trim().isEmpty()) {
            return Optional.empty();
        }

        String raw = input.trim();
        String normalized = raw.toLowerCase(Locale.ROOT);

        Set<String> dictionary = getDictionary();

        // If the exact query matches known term (case-insensitive), no typo correction needed
        for (String word : dictionary) {
            if (word.equalsIgnoreCase(normalized)) {
                return Optional.empty();
            }
        }

        // 1. Check full-query matching first
        String bestCandidate = null;
        int minDistance = Integer.MAX_VALUE;

        for (String word : dictionary) {
            int dist = levenshteinDistance(normalized, word.toLowerCase(Locale.ROOT));
            if (dist <= 2 && dist < minDistance) {
                minDistance = dist;
                bestCandidate = word;
            }
        }

        if (bestCandidate != null && minDistance <= 2) {
            return Optional.of(FuzzySearchResult.builder()
                    .originalQuery(raw)
                    .suggestedQuery(bestCandidate)
                    .fuzzyMatched(true)
                    .distance(minDistance)
                    .build());
        }

        // 2. Token-level matching for multi-word queries (e.g. "chairzard vmax")
        String[] tokens = raw.split("\\s+");
        if (tokens.length > 1) {
            StringBuilder suggested = new StringBuilder();
            boolean anyChanged = false;
            int totalDist = 0;

            for (int i = 0; i < tokens.length; i++) {
                String token = tokens[i];
                String tokenBest = token;
                int tokenMinDist = Integer.MAX_VALUE;

                for (String word : dictionary) {
                    int dist = levenshteinDistance(token.toLowerCase(Locale.ROOT), word.toLowerCase(Locale.ROOT));
                    if (dist <= 2 && dist < tokenMinDist) {
                        tokenMinDist = dist;
                        tokenBest = word;
                    }
                }

                if (tokenMinDist <= 2 && tokenMinDist > 0) {
                    anyChanged = true;
                    totalDist += tokenMinDist;
                    suggested.append(tokenBest);
                } else {
                    suggested.append(token);
                }

                if (i < tokens.length - 1) {
                    suggested.append(" ");
                }
            }

            if (anyChanged && totalDist <= 2) {
                return Optional.of(FuzzySearchResult.builder()
                        .originalQuery(raw)
                        .suggestedQuery(suggested.toString())
                        .fuzzyMatched(true)
                        .distance(totalDist)
                        .build());
            }
        }

        return Optional.empty();
    }

    public Set<String> getDictionary() {
        Set<String> words = new LinkedHashSet<>(BASE_DICTIONARY);
        if (cardRepository != null) {
            try {
                List<String> names = cardRepository.findDistinctNames();
                if (names != null) {
                    for (String name : names) {
                        if (name != null && !name.isBlank()) {
                            words.add(name);
                            // Also add single word tokens (e.g. "Charizard" from "Charizard VMAX")
                            for (String token : name.split("\\s+")) {
                                if (token.length() >= 3) {
                                    words.add(token);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Could not load dynamic card names from repository: {}", e.getMessage());
            }
        }
        return words;
    }
}

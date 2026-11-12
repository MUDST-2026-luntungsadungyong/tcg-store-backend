package com.mythix.tcg.catalog.persistence;

import com.mythix.tcg.catalog.domain.CardSearchName;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CardNameSearch {

    private static final Pattern GAME_PREFIX = Pattern.compile("^(?:โปเกมอน|pokémon|pokemon)(?: |$)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern THAI_SUFFIX_GAP = Pattern.compile("(?<=\\p{IsThai}) (?=(?:ex|gx|vstar|vmax|v)\\b)");
    private static final Pattern HYBRID_PATTERN = Pattern.compile("^(.*?)\\s+([a-zA-Z0-9/\\-_#]+)$");
    private static final Pattern HAS_DIGIT = Pattern.compile(".*\\d.*");

    private static final Map<String, String> THAI_ALIAS_FALLBACK = Map.of(
            "โปเกบอล", "poke ball",
            "โปเกมอน", "pokemon",
            "ลิซาร์ดอน", "charizard",
            "พิคาชู", "pikachu",
            "ปิกาจู", "pikachu",
            "มิว", "mew",
            "มิวทู", "mewtwo"
    );

    private CardNameSearch() {}

    public static String term(String input) {
        if (input == null) {
            return "";
        }
        String normalized = WHITESPACE.matcher(Normalizer.normalize(input, Normalizer.Form.NFC))
            .replaceAll(" ").trim().toLowerCase(Locale.ROOT);
        return GAME_PREFIX.matcher(normalized).replaceFirst("");
    }

    public static Predicate matches(Path<?> card, CriteriaQuery<?> query, CriteriaBuilder cb, String term) {
        if (term == null || term.isBlank()) {
            return cb.conjunction();
        }

        // Check for Hybrid Search: Name/Thai + Card Number (e.g. "ลิซาร์ดอน 006", "Charizard 4")
        Matcher hybridMatcher = HYBRID_PATTERN.matcher(term);
        if (hybridMatcher.matches()) {
            String textPart = hybridMatcher.group(1).trim();
            String numPart = hybridMatcher.group(2).trim();

            if (!textPart.isEmpty() && HAS_DIGIT.matcher(numPart).matches()) {
                Predicate textMatch = matchesName(card, query, cb, textPart);
                Predicate numMatch = cb.or(
                        cb.like(cb.lower(card.get("cardNumber")), contains(numPart), '!'),
                        cb.like(cb.lower(card.get("id")), contains(numPart), '!')
                );
                Predicate hybrid = cb.and(textMatch, numMatch);
                return cb.or(hybrid, matchesName(card, query, cb, term));
            }
        }

        return matchesName(card, query, cb, term);
    }

    private static Predicate matchesName(Path<?> card, CriteriaQuery<?> query, CriteriaBuilder cb, String term) {
        Subquery<Integer> names = query.subquery(Integer.class);
        Root<CardSearchName> alias = names.from(CardSearchName.class);
        String aliasTerm = THAI_SUFFIX_GAP.matcher(term).replaceAll("");
        names.select(cb.literal(1)).where(
            cb.equal(alias.get("cardId"), card.get("id")),
            cb.like(alias.get("searchName"), contains(aliasTerm), '!')
        );

        List<Predicate> orPredicates = new ArrayList<>();
        orPredicates.add(cb.like(cb.lower(card.get("name")), contains(term), '!'));
        orPredicates.add(cb.exists(names));

        // Fallback for well-known Thai names to match English card catalog
        String lowerTerm = term.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : THAI_ALIAS_FALLBACK.entrySet()) {
            if (lowerTerm.equals(entry.getKey())) {
                orPredicates.add(cb.like(cb.lower(card.get("name")), contains(entry.getValue()), '!'));
            } else if (lowerTerm.startsWith(entry.getKey())) {
                String remainder = lowerTerm.substring(entry.getKey().length()).trim();
                if (!remainder.isEmpty()) {
                    orPredicates.add(cb.and(
                            cb.like(cb.lower(card.get("name")), contains(entry.getValue()), '!'),
                            cb.like(cb.lower(card.get("name")), contains(remainder), '!')
                    ));
                }
            }
        }

        return cb.or(orPredicates.toArray(new Predicate[0]));
    }

    private static String contains(String text) {
        return "%" + text.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}

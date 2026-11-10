package com.mythix.tcg.catalog.persistence;

import com.mythix.tcg.catalog.domain.CardSearchName;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class CardNameSearch {

    private static final Pattern GAME_PREFIX = Pattern.compile("^(?:โปเกมอน|pokémon|pokemon)(?: |$)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern THAI_SUFFIX_GAP = Pattern.compile("(?<=\\p{IsThai}) (?=(?:ex|gx|vstar|vmax|v)\\b)");

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
        Subquery<Integer> names = query.subquery(Integer.class);
        Root<CardSearchName> alias = names.from(CardSearchName.class);
        String aliasTerm = THAI_SUFFIX_GAP.matcher(term).replaceAll("");
        names.select(cb.literal(1)).where(
            cb.equal(alias.get("cardId"), card.get("id")),
            cb.like(alias.get("searchName"), contains(aliasTerm), '!')
        );
        return cb.or(cb.like(cb.lower(card.get("name")), contains(term), '!'), cb.exists(names));
    }

    private static String contains(String text) {
        return "%" + text.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}

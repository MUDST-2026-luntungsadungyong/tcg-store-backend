package com.mythix.tcg.seller.application;

import com.mythix.tcg.seller.api.ListingRulesResponse;
import java.math.BigDecimal;
import java.util.*;

/** Deterministic US-20 policy. Only known low-risk rarity codes qualify for optional photos. */
public final class ListingImagePolicy {
    private ListingImagePolicy() {}
    public static final int MAX_ADDITIONAL_IMAGES = 4;

    public static ListingRulesResponse evaluate(String game, String rarity, BigDecimal price,
        String condition, String gradingCompany) {
        List<String> reasons = new ArrayList<>();
        if (price.compareTo(new BigDecimal("500")) >= 0) reasons.add("HIGH_PRICE");
        if (gradingCompany != null && !gradingCompany.isBlank()) reasons.add("GRADED");
        if (!"NM".equals(condition)) reasons.add("CONDITION");
        String normalized = normalize(rarity);
        Set<String> optional = switch (normalize(game)) {
            // The imported Pokemon catalog uses TCG supertypes rather than a
            // separate game field, including Trainer and Energy cards.
            case "POKEMON", "POKÉMON", "TRAINER", "ENERGY" -> Set.of("COMMON", "UNCOMMON", "REGULAR", "C", "U");
            case "YGO", "YU-GI-OH", "YU-GI-OH!" -> Set.of("COMMON", "NORMAL", "C");
            case "MTG", "MAGIC: THE GATHERING" -> Set.of("COMMON", "UNCOMMON", "C", "U");
            case "ONE PIECE" -> Set.of("COMMON", "UNCOMMON", "C", "UC");
            default -> Set.of();
        };
        if (!optional.contains(normalized)) {
            // All non-allowlisted rarities require photos. Recognized special
            // rarities get a useful reason, unknown/missing values fail closed.
            Set<String> special = Set.of("SR", "SAR", "ALT-ART", "ALT ART", "ALTERNATE ART",
                "SECRET RARE", "RARE SECRET", "RARE ULTRA", "ULTRA RARE", "SUPER RARE",
                "SPECIAL ART RARE", "SPECIAL ILLUSTRATION RARE", "RARE HOLO",
                "RARE", "R", "MYTHIC", "MYTHIC RARE", "LEADER", "L", "SEC", "SP");
            reasons.add(special.contains(normalized) ? "SPECIAL_RARITY" : "UNKNOWN_RARITY");
        }
        boolean mandatory = !reasons.isEmpty();
        return new ListingRulesResponse(mandatory, List.copyOf(reasons), mandatory ? 2 : 0,
            MAX_ADDITIONAL_IMAGES, SellerProductImageValidator.MAX_IMAGE_SIZE_BYTES,
            List.of("image/jpeg", "image/png", "image/webp"));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }
}

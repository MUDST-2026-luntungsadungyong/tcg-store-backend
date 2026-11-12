package com.mythix.tcg.product.domain;

import java.util.Locale;

public final class CardCondition {
    private CardCondition() {}

    public static String code(String condition) {
        if (condition == null) {
            return null;
        }
        return switch (condition.trim().toUpperCase(Locale.ROOT).replace('-', ' ').replace('_', ' ')) {
            case "NM", "NEAR MINT" -> "NM";
            case "LP", "LIGHTLY PLAYED" -> "LP";
            case "MP", "MODERATELY PLAYED" -> "MP";
            case "HP", "HEAVILY PLAYED" -> "HP";
            case "DMG", "DAMAGED" -> "DMG";
            default -> null;
        };
    }
}

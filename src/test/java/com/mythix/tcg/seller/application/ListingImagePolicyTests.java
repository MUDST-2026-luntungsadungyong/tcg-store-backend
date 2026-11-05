package com.mythix.tcg.seller.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class ListingImagePolicyTests {
    @ParameterizedTest
    @CsvSource({"Pokemon, Common", "Pokémon, Uncommon", "Pokemon, Regular", "YGO, Normal",
        "Yu-Gi-Oh!, Common", "MTG, Uncommon", "One Piece, UC", "Trainer, Uncommon", "Energy, Common"})
    void recognizedLowRiskCatalogRaritiesAllowOptionalPhotos(String game, String rarity) {
        var rules = ListingImagePolicy.evaluate(game, rarity, new BigDecimal("499.99"), "NM", null);
        assertThat(rules.imageMandatory()).isFalse();
        assertThat(rules.minimumActualPhotos()).isZero();
        assertThat(rules.reasons()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"499.99, false", "500.00, true", "500.01, true"})
    void thresholdIncludesExactlyFiveHundred(String price, boolean mandatory) {
        assertThat(ListingImagePolicy.evaluate("Pokemon", "Common", new BigDecimal(price), "NM", null)
            .imageMandatory()).isEqualTo(mandatory);
    }

    @ParameterizedTest
    @CsvSource({"LP", "MP", "HP", "DMG"})
    void conditionMakesLowValueCardsMandatory(String condition) {
        assertThat(ListingImagePolicy.evaluate("Pokemon", "Common", BigDecimal.ONE, condition, null)
            .reasons()).containsExactly("CONDITION");
    }

    @ParameterizedTest
    @CsvSource({"PSA", "BGS", "CGC"})
    void allSupportedGradingCompaniesRequirePhotos(String company) {
        assertThat(ListingImagePolicy.evaluate("Pokemon", "Common", BigDecimal.ONE, "NM", company)
            .reasons()).containsExactly("GRADED");
    }

    @ParameterizedTest
    @CsvSource({"Pokemon, SR", "Pokemon, SAR", "Pokemon, Alt-Art", "Pokemon, Rare Ultra",
        "Pokemon, Rare Holo", "YGO, Super Rare", "MTG, Mythic Rare", "One Piece, SEC"})
    void recognizedSpecialRaritiesRequirePhotos(String game, String rarity) {
        assertThat(ListingImagePolicy.evaluate(game, rarity, BigDecimal.ONE, "NM", null)
            .reasons()).containsExactly("SPECIAL_RARITY");
    }

    @Test
    void unknownGameMissingRarityAndWrongGameCodeNeverQualifyAsOptional() {
        assertThat(ListingImagePolicy.evaluate("unknown", "Common", BigDecimal.ONE, "NM", null)
            .imageMandatory()).isTrue();
        assertThat(ListingImagePolicy.evaluate("Pokemon", null, BigDecimal.ONE, "NM", null)
            .reasons()).containsExactly("UNKNOWN_RARITY");
        assertThat(ListingImagePolicy.evaluate("Pokemon", "UC", BigDecimal.ONE, "NM", null)
            .imageMandatory()).isTrue();
    }

    @Test
    void reasonsAreAdditiveAndLimitsMatchUploadValidator() {
        var rules = ListingImagePolicy.evaluate("Pokemon", "SAR", new BigDecimal("500"), "LP", "PSA");
        assertThat(rules.reasons()).containsExactly("HIGH_PRICE", "GRADED", "CONDITION", "SPECIAL_RARITY");
        assertThat(rules.minimumActualPhotos()).isEqualTo(2);
        assertThat(rules.maxAdditionalImages()).isEqualTo(4);
        assertThat(rules.maxImageSizeBytes()).isEqualTo(5 * 1024 * 1024);
        assertThat(rules.allowedImageTypes()).containsExactly("image/jpeg", "image/png", "image/webp");
    }
}

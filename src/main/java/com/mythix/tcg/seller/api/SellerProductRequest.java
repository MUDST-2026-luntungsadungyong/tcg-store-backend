package com.mythix.tcg.seller.api;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record SellerProductRequest(
    @NotBlank(message = "Product name is required")
    @Size(max = 255, message = "Product name must be at most 255 characters")
    String name,

    @NotBlank(message = "Game is required")
    @Pattern(
        regexp = "(?i)Pokemon|Pokémon|YGO|Yu-Gi-Oh!?|MTG|Magic: The Gathering|One Piece",
        message = "Game must be Pokemon, YGO, MTG, or One Piece"
    )
    String game,

    @JsonAlias("imageUrl")
    @NotBlank(message = "Product image or image URL is required")
    @Size(max = 500, message = "Image URL must be at most 500 characters")
    String image,

    @NotNull(message = "Sell price is required")
    @DecimalMin(value = "0.00", message = "Sell price must be 0 or more")
    @Digits(integer = 10, fraction = 2, message = "Sell price must have at most 2 decimal places")
    BigDecimal price,

    @NotNull(message = "Cost is required")
    @DecimalMin(value = "0.00", message = "Cost must be 0 or more")
    @Digits(integer = 10, fraction = 2, message = "Cost must have at most 2 decimal places")
    BigDecimal cost,

    @NotNull(message = "Stock is required")
    @Min(value = 0, message = "Stock must be 0 or more")
    Integer stock,

    @NotBlank(message = "Status is required")
    @Pattern(regexp = "SHOW|HIDE", message = "Status must be SHOW or HIDE")
    String status,

    @Size(max = 50, message = "Condition must be at most 50 characters")
    String condition,

    @Size(max = 255, message = "Set must be at most 255 characters")
    String set,

    @Size(max = 255, message = "Rarity must be at most 255 characters")
    String rarity
) {
}

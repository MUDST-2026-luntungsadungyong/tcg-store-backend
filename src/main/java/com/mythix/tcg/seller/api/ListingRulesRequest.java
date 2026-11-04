package com.mythix.tcg.seller.api;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record ListingRulesRequest(
    @NotBlank @Size(max = 100) String cardId,
    @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal price,
    @Size(max = 50) String condition,
    @Pattern(regexp = "(?i)(PSA|BGS|CGC)?") String gradingCompany
) {}

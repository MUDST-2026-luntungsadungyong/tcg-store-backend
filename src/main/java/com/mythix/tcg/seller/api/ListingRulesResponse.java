package com.mythix.tcg.seller.api;

import java.util.List;

public record ListingRulesResponse(
    boolean imageMandatory,
    List<String> reasons,
    int minimumActualPhotos,
    int maxAdditionalImages,
    long maxImageSizeBytes,
    List<String> allowedImageTypes
) {}

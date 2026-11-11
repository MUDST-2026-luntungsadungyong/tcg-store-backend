package com.mythix.tcg.catalog.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FuzzySearchResult {
    private String originalQuery;
    private String suggestedQuery;
    private boolean fuzzyMatched;
    private int distance;
}

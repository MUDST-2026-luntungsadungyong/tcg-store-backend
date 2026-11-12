package com.mythix.tcg.catalog.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

public class CardSearchPage<T> extends PageImpl<T> {

    private final String didYouMean;
    private final String originalQuery;
    private final Boolean fuzzyMatched;

    public CardSearchPage(List<T> content, Pageable pageable, long total,
                          String didYouMean, String originalQuery, Boolean fuzzyMatched) {
        super(content, pageable, total);
        this.didYouMean = didYouMean;
        this.originalQuery = originalQuery;
        this.fuzzyMatched = fuzzyMatched;
    }

    public CardSearchPage(Page<T> page, String didYouMean, String originalQuery, Boolean fuzzyMatched) {
        super(page.getContent(), page.getPageable(), page.getTotalElements());
        this.didYouMean = didYouMean;
        this.originalQuery = originalQuery;
        this.fuzzyMatched = fuzzyMatched;
    }

    @JsonProperty("didYouMean")
    public String getDidYouMean() {
        return didYouMean;
    }

    @JsonProperty("originalQuery")
    public String getOriginalQuery() {
        return originalQuery;
    }

    @JsonProperty("fuzzyMatched")
    public Boolean getFuzzyMatched() {
        return fuzzyMatched;
    }
}

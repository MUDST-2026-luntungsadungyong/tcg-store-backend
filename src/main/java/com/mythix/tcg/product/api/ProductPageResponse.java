package com.mythix.tcg.product.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductPageResponse {

    private List<ProductResponse> items;
    private int pageNumber;
    private int pageSize;
    private long totalElements;
    private int totalPages;
    private boolean last;
    private String didYouMean;
    private String originalQuery;
    private Boolean fuzzyMatched;

    public static ProductPageResponse fromPage(Page<ProductResponse> page) {
        return ProductPageResponse.builder()
                .items(page.getContent())
                .pageNumber(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }

    public static ProductPageResponse fromPage(Page<ProductResponse> page, String didYouMean, String originalQuery, Boolean fuzzyMatched) {
        return ProductPageResponse.builder()
                .items(page.getContent())
                .pageNumber(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .didYouMean(didYouMean)
                .originalQuery(originalQuery)
                .fuzzyMatched(fuzzyMatched)
                .build();
    }
}

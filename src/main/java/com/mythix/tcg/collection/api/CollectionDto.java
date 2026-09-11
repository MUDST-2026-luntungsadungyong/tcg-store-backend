package com.mythix.tcg.collection.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

public class CollectionDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AddToCollectionRequest {
        private String cardId;
        private String listingId;

        private String condition;

        @Builder.Default
        private String language = "EN";

        @Min(value = 1, message = "quantity must be at least 1")
        @Builder.Default
        private Integer quantity = 1;

        public String resolveCardId() {
            if (cardId != null && !cardId.isBlank()) {
                return cardId.trim();
            }
            if (listingId != null && !listingId.isBlank()) {
                return listingId.trim();
            }
            return null;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CardListingDto {
        private String id;
        private String name;
        private String game;
        private String image;
        private String set;
        private String rarity;
        private String cardNumber;
        private String condition;
        private String status;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CollectionItemResponse {
        private String id;
        private String ownerId;
        private String cardId;
        private String cardName;
        private String game;
        private String setName;
        private String cardNumber;
        private String rarity;
        private String imageUrl;
        private String condition;
        private String language;
        private Integer quantity;
        private String createdAt;
        private String acquiredAt;
        private CardListingDto listing;
    }
}

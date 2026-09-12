package com.mythix.tcg.collection.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Public, buyer-safe representations used by the homepage leaderboard and profiles. */
public final class PublicCollectionDto {

    private PublicCollectionDto() {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LeaderboardResponse {
        private List<LeaderboardEntry> items;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LeaderboardEntry {
        private int rank;
        private String userId;
        private String displayName;
        private String avatarUrl;
        private long rareCardCount;
        private long distinctRareCardCount;
        private List<RareCardHighlight> highlights;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RareCardHighlight {
        private String cardId;
        private String cardName;
        private String imageUrl;
        private String rarity;
        private int quantity;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PublicCollectionResponse {
        private PublicCollector owner;
        private List<CollectionDto.CollectionItemResponse> items;
        private CollectionDto.PageMetadata page;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PublicCollector {
        private String userId;
        private String displayName;
        private String avatarUrl;
        private long rareCardCount;
        private long distinctRareCardCount;
    }
}

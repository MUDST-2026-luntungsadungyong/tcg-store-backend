package com.mythix.tcg.collection.application;

import com.mythix.tcg.collection.api.CollectionDto.CollectionItemResponse;
import com.mythix.tcg.collection.api.CollectionDto.PageMetadata;
import com.mythix.tcg.collection.api.PublicCollectionDto;
import com.mythix.tcg.collection.api.PublicCollectionDto.CardComparisonEntry;
import com.mythix.tcg.collection.api.PublicCollectionDto.CollectionComparisonResponse;
import com.mythix.tcg.collection.api.PublicCollectionDto.LeaderboardEntry;
import com.mythix.tcg.collection.api.PublicCollectionDto.RareCardHighlight;
import com.mythix.tcg.collection.domain.CollectionItem;
import com.mythix.tcg.collection.persistence.CollectionRepository;
import com.mythix.tcg.collection.persistence.CollectionRepository.RareCollectionAggregate;
import com.mythix.tcg.collection.persistence.CollectionRepository.RareCollectionHighlight;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PublicCollectionService {

    private static final int HIGHLIGHT_LIMIT = 3;

    private final CollectionRepository collectionRepository;
    private final UserRepository userRepository;
    private final CollectionService collectionService;

    @Transactional(readOnly = true)
    public PublicCollectionDto.LeaderboardResponse getLeaderboard(Pageable pageable) {
        List<RareCollectionAggregate> aggregates = collectionRepository.findRareCollectionAggregates(pageable);
        if (aggregates.isEmpty()) {
            return PublicCollectionDto.LeaderboardResponse.builder().items(List.of()).build();
        }

        List<Long> userIds = aggregates.stream().map(RareCollectionAggregate::getUserId).toList();
        Map<Long, User> users = indexUsers(userRepository.findAllById(userIds));
        Map<Long, List<RareCollectionHighlight>> highlights = groupHighlights(
                collectionRepository.findRareCollectionHighlights(userIds));

        List<LeaderboardEntry> entries = new ArrayList<>(aggregates.size());
        for (int index = 0; index < aggregates.size(); index++) {
            RareCollectionAggregate aggregate = aggregates.get(index);
            User user = users.get(aggregate.getUserId());
            if (user == null) {
                continue;
            }
            entries.add(toLeaderboardEntry(index + 1, aggregate, user, highlights.getOrDefault(aggregate.getUserId(), List.of())));
        }
        return PublicCollectionDto.LeaderboardResponse.builder().items(entries).build();
    }

    @Transactional(readOnly = true)
    public PublicCollectionDto.PublicCollectionResponse getPublicCollection(Long userId, Pageable pageable) {
        return getPublicCollection(userId, null, pageable);
    }

    @Transactional(readOnly = true)
    public PublicCollectionDto.PublicCollectionResponse getPublicCollection(Long userId, Long currentUserId, Pageable pageable) {
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new PublicCollectionUserNotFoundException(userId));

        if ("PRIVATE".equalsIgnoreCase(owner.getCollectionPrivacy())
                && (currentUserId == null || !currentUserId.equals(userId))) {
            throw new PublicCollectionPrivateException(userId);
        }

        List<CollectionItem> allOwnerItems = collectionRepository.findByUserIdOrderByCreatedAtDesc(userId);
        Page<CollectionItemResponse> page = collectionRepository
                .findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(collectionService::mapToResponse);
        RareCollectionAggregate aggregate = collectionRepository.findRareCollectionAggregate(userId).orElse(null);

        CollectionComparisonResponse comparison = null;
        List<CollectionItemResponse> items = page.getContent();
        if (currentUserId != null) {
            comparison = buildComparison(owner, allOwnerItems, currentUserId);
            Map<String, Integer> ownedQuantities = comparison.getOwnedQuantities();
            items = items.stream().map(item -> {
                String cardId = item.getCardId();
                int visitorQty = cardId != null ? ownedQuantities.getOrDefault(cardId, 0) : 0;
                item.setVisitorOwnedQuantity(visitorQty);
                return item;
            }).toList();
        }

        return PublicCollectionDto.PublicCollectionResponse.builder()
                .owner(toPublicCollector(owner, aggregate, allOwnerItems))
                .items(items)
                .page(PageMetadata.builder()
                        .page(page.getNumber())
                        .size(page.getSize())
                        .totalElements(page.getTotalElements())
                        .totalPages(page.getTotalPages())
                        .build())
                .comparison(comparison)
                .build();
    }

    @Transactional(readOnly = true)
    public CollectionComparisonResponse compareCollection(Long targetUserId, Long visitorUserId) {
        User owner = userRepository.findById(targetUserId)
                .orElseThrow(() -> new PublicCollectionUserNotFoundException(targetUserId));

        if ("PRIVATE".equalsIgnoreCase(owner.getCollectionPrivacy())
                && (visitorUserId == null || !visitorUserId.equals(targetUserId))) {
            throw new PublicCollectionPrivateException(targetUserId);
        }

        List<CollectionItem> targetItems = collectionRepository.findByUserIdOrderByCreatedAtDesc(targetUserId);
        return buildComparison(owner, targetItems, visitorUserId);
    }

    private CollectionComparisonResponse buildComparison(
            User targetOwner,
            List<CollectionItem> targetItems,
            Long visitorUserId) {
        Map<String, Integer> visitorOwnedByCardId = new LinkedHashMap<>();
        if (visitorUserId != null) {
            List<CollectionItem> visitorItems = collectionRepository.findByUserIdOrderByCreatedAtDesc(visitorUserId);
            for (CollectionItem item : visitorItems) {
                if (item.getCard() != null && item.getCard().getId() != null) {
                    String cardId = item.getCard().getId();
                    int qty = item.getQuantity() != null ? item.getQuantity() : 0;
                    visitorOwnedByCardId.merge(cardId, qty, Integer::sum);
                }
            }
        }

        Map<String, Integer> targetQtyByCardId = new LinkedHashMap<>();
        Map<String, String> cardNameByCardId = new LinkedHashMap<>();
        for (CollectionItem item : targetItems) {
            if (item.getCard() != null && item.getCard().getId() != null) {
                String cardId = item.getCard().getId();
                int qty = item.getQuantity() != null ? item.getQuantity() : 0;
                targetQtyByCardId.merge(cardId, qty, Integer::sum);
                cardNameByCardId.putIfAbsent(cardId, item.getCard().getName() != null ? item.getCard().getName() : cardId);
            }
        }

        List<CardComparisonEntry> entries = new ArrayList<>(targetQtyByCardId.size());
        Map<String, Integer> ownedQuantitiesForTarget = new LinkedHashMap<>();
        int sharedDistinctCards = 0;
        for (Map.Entry<String, Integer> targetEntry : targetQtyByCardId.entrySet()) {
            String cardId = targetEntry.getKey();
            int targetQuantity = targetEntry.getValue();
            int visitorQuantity = visitorOwnedByCardId.getOrDefault(cardId, 0);
            boolean owned = visitorQuantity > 0;
            if (owned) {
                sharedDistinctCards++;
            }
            ownedQuantitiesForTarget.put(cardId, visitorQuantity);
            entries.add(CardComparisonEntry.builder()
                    .cardId(cardId)
                    .cardName(cardNameByCardId.getOrDefault(cardId, cardId))
                    .targetQuantity(targetQuantity)
                    .visitorQuantity(visitorQuantity)
                    .owned(owned)
                    .build());
        }

        int totalTargetDistinctCards = targetQtyByCardId.size();
        int missingDistinctCards = Math.max(0, totalTargetDistinctCards - sharedDistinctCards);

        return CollectionComparisonResponse.builder()
                .targetUserId(String.valueOf(targetOwner.getId()))
                .visitorUserId(visitorUserId != null ? String.valueOf(visitorUserId) : null)
                .totalTargetDistinctCards(totalTargetDistinctCards)
                .sharedDistinctCards(sharedDistinctCards)
                .missingDistinctCards(missingDistinctCards)
                .ownedQuantities(ownedQuantitiesForTarget)
                .items(entries)
                .build();
    }

    private Map<Long, User> indexUsers(Iterable<User> users) {
        Map<Long, User> indexed = new HashMap<>();
        users.forEach(user -> indexed.put(user.getId(), user));
        return indexed;
    }

    private Map<Long, List<RareCollectionHighlight>> groupHighlights(Collection<RareCollectionHighlight> rows) {
        Map<Long, List<RareCollectionHighlight>> grouped = new LinkedHashMap<>();
        for (RareCollectionHighlight row : rows) {
            List<RareCollectionHighlight> userHighlights = grouped.computeIfAbsent(row.getUserId(), ignored -> new ArrayList<>());
            if (userHighlights.size() < HIGHLIGHT_LIMIT) {
                userHighlights.add(row);
            }
        }
        return grouped;
    }

    private LeaderboardEntry toLeaderboardEntry(
            int rank,
            RareCollectionAggregate aggregate,
            User user,
            List<RareCollectionHighlight> highlightRows) {
        return LeaderboardEntry.builder()
                .rank(rank)
                .userId(String.valueOf(user.getId()))
                .displayName(publicDisplayName(user))
                .avatarUrl(user.getAvatarUrl())
                .rareCardCount(nonNullCount(aggregate.getRareCardCount()))
                .distinctRareCardCount(nonNullCount(aggregate.getDistinctRareCardCount()))
                .highlights(highlightRows.stream().map(this::toHighlight).toList())
                .build();
    }

    private PublicCollectionDto.PublicCollector toPublicCollector(
            User user,
            RareCollectionAggregate aggregate,
            List<CollectionItem> allOwnerItems) {
        long rareCardCount = aggregate == null ? 0 : nonNullCount(aggregate.getRareCardCount());
        long distinctRareCardCount = aggregate == null ? 0 : nonNullCount(aggregate.getDistinctRareCardCount());
        long totalCardsCount = 0;
        long secretRareCount = 0;
        for (CollectionItem item : allOwnerItems) {
            int qty = item.getQuantity() != null ? item.getQuantity() : 0;
            totalCardsCount += qty;
            String rarity = item.getCard() != null && item.getCard().getRarity() != null
                    ? item.getCard().getRarity().toLowerCase(Locale.ROOT)
                    : "";
            if (rarity.contains("secret") || rarity.equals("sar") || rarity.equals("sr") || rarity.contains("alt")) {
                secretRareCount += qty;
            }
        }

        List<String> badges = new ArrayList<>();
        if ("APPROVED".equalsIgnoreCase(user.getSellerStatus()) || "SELLER".equalsIgnoreCase(user.getRole())) {
            badges.add("Verified Seller");
        }
        if (secretRareCount > 0) {
            badges.add("Secret Rare Hunter");
        }
        if (rareCardCount >= 10) {
            badges.add("Master Collector");
        } else if (totalCardsCount >= 5) {
            badges.add("Active Collector");
        }

        return PublicCollectionDto.PublicCollector.builder()
                .userId(String.valueOf(user.getId()))
                .displayName(publicDisplayName(user))
                .avatarUrl(user.getAvatarUrl())
                .role(user.getRole())
                .sellerStatus(user.getSellerStatus())
                .collectionPrivacy(user.getCollectionPrivacy() != null ? user.getCollectionPrivacy() : "PUBLIC")
                .totalCardsCount(totalCardsCount)
                .rareCardCount(rareCardCount)
                .distinctRareCardCount(distinctRareCardCount)
                .secretRareCount(secretRareCount)
                .badges(badges)
                .build();
    }

    private RareCardHighlight toHighlight(RareCollectionHighlight row) {
        String imageUrl = row.getImageLarge() != null ? row.getImageLarge() : row.getImageSmall();
        return RareCardHighlight.builder()
                .cardId(row.getCardId())
                .cardName(row.getCardName())
                .imageUrl(imageUrl)
                .rarity(row.getRarity())
                .quantity(row.getQuantity() == null ? 0 : row.getQuantity())
                .build();
    }

    private String publicDisplayName(User user) {
        return user.getFullName() == null || user.getFullName().isBlank()
                ? "Collector"
                : user.getFullName();
    }

    private long nonNullCount(Long count) {
        return Optional.ofNullable(count).orElse(0L);
    }

    @Transactional(readOnly = true)
    public PublicCollectionDto.PublicCollectionsPageResponse getPublicCollections(String search, Pageable pageable) {
        List<Long> publicUserIds = collectionRepository.findPublicUserIdsWithCollection();
        if (publicUserIds.isEmpty()) {
            return PublicCollectionDto.PublicCollectionsPageResponse.builder()
                    .items(List.of())
                    .page(PageMetadata.builder()
                            .page(pageable.getPageNumber())
                            .size(pageable.getPageSize())
                            .totalElements(0)
                            .totalPages(0)
                            .build())
                    .build();
        }

        List<User> publicUsers = userRepository.findAllById(publicUserIds);
        if (search != null && !search.isBlank()) {
            String query = search.trim().toLowerCase();
            publicUsers = publicUsers.stream()
                    .filter(u -> publicDisplayName(u).toLowerCase().contains(query))
                    .toList();
        }

        if (publicUsers.isEmpty()) {
            return PublicCollectionDto.PublicCollectionsPageResponse.builder()
                    .items(List.of())
                    .page(PageMetadata.builder()
                            .page(pageable.getPageNumber())
                            .size(pageable.getPageSize())
                            .totalElements(0)
                            .totalPages(0)
                            .build())
                    .build();
        }

        List<Long> filteredIds = publicUsers.stream().map(User::getId).toList();
        Map<Long, List<RareCollectionHighlight>> highlightRows = groupHighlights(
                collectionRepository.findRareCollectionHighlights(filteredIds));

        Map<Long, Long> totalCounts = new HashMap<>();
        collectionRepository.findTotalCardCounts(filteredIds)
                .forEach(tc -> totalCounts.put(tc.getUserId(), tc.getTotalCardCount()));

        Map<Long, RareCollectionAggregate> rareAggregates = new HashMap<>();
        for (Long uid : filteredIds) {
            collectionRepository.findRareCollectionAggregate(uid)
                    .ifPresent(agg -> rareAggregates.put(uid, agg));
        }

        List<PublicCollectionDto.PublicCollectionSummary> summaries = new ArrayList<>(publicUsers.size());
        for (User user : publicUsers) {
            RareCollectionAggregate aggregate = rareAggregates.get(user.getId());
            boolean isSeller = "APPROVED".equalsIgnoreCase(user.getSellerStatus()) || "SELLER".equalsIgnoreCase(user.getRole());
            summaries.add(PublicCollectionDto.PublicCollectionSummary.builder()
                    .userId(String.valueOf(user.getId()))
                    .displayName(publicDisplayName(user))
                    .avatarUrl(user.getAvatarUrl())
                    .role(user.getRole())
                    .sellerStatus(user.getSellerStatus())
                    .isSeller(isSeller)
                    .totalCardsCount(totalCounts.getOrDefault(user.getId(), 0L))
                    .rareCardCount(aggregate == null ? 0L : nonNullCount(aggregate.getRareCardCount()))
                    .distinctRareCardCount(aggregate == null ? 0L : nonNullCount(aggregate.getDistinctRareCardCount()))
                    .highlights(highlightRows.getOrDefault(user.getId(), List.of()).stream().map(this::toHighlight).toList())
                    .build());
        }

        summaries.sort((a, b) -> {
            int cmp = Long.compare(b.getRareCardCount(), a.getRareCardCount());
            if (cmp != 0) return cmp;
            return Long.compare(b.getTotalCardsCount(), a.getTotalCardsCount());
        });

        int totalElements = summaries.size();
        int totalPages = (int) Math.ceil((double) totalElements / pageable.getPageSize());
        int fromIndex = (int) Math.min(pageable.getOffset(), totalElements);
        int toIndex = Math.min(fromIndex + pageable.getPageSize(), totalElements);
        List<PublicCollectionDto.PublicCollectionSummary> paged = summaries.subList(fromIndex, toIndex);

        return PublicCollectionDto.PublicCollectionsPageResponse.builder()
                .items(paged)
                .page(PageMetadata.builder()
                        .page(pageable.getPageNumber())
                        .size(pageable.getPageSize())
                        .totalElements(totalElements)
                        .totalPages(totalPages)
                        .build())
                .build();
    }
}

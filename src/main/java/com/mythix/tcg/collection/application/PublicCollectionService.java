package com.mythix.tcg.collection.application;

import com.mythix.tcg.collection.api.CollectionDto.CollectionItemResponse;
import com.mythix.tcg.collection.api.CollectionDto.PageMetadata;
import com.mythix.tcg.collection.api.PublicCollectionDto;
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
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new PublicCollectionUserNotFoundException(userId));

        Page<CollectionItemResponse> page = collectionRepository
                .findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(collectionService::mapToResponse);
        RareCollectionAggregate aggregate = collectionRepository.findRareCollectionAggregate(userId).orElse(null);

        return PublicCollectionDto.PublicCollectionResponse.builder()
                .owner(toPublicCollector(owner, aggregate))
                .items(page.getContent())
                .page(PageMetadata.builder()
                        .page(page.getNumber())
                        .size(page.getSize())
                        .totalElements(page.getTotalElements())
                        .totalPages(page.getTotalPages())
                        .build())
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

    private PublicCollectionDto.PublicCollector toPublicCollector(User user, RareCollectionAggregate aggregate) {
        return PublicCollectionDto.PublicCollector.builder()
                .userId(String.valueOf(user.getId()))
                .displayName(publicDisplayName(user))
                .avatarUrl(user.getAvatarUrl())
                .rareCardCount(aggregate == null ? 0 : nonNullCount(aggregate.getRareCardCount()))
                .distinctRareCardCount(aggregate == null ? 0 : nonNullCount(aggregate.getDistinctRareCardCount()))
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
}

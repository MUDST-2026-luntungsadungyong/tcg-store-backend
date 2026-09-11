package com.mythix.tcg.collection.application;

import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.collection.api.CollectionDto.AddToCollectionRequest;
import com.mythix.tcg.collection.api.CollectionDto.CardListingDto;
import com.mythix.tcg.collection.api.CollectionDto.CollectionItemResponse;
import com.mythix.tcg.collection.domain.CollectionItem;
import com.mythix.tcg.collection.persistence.CollectionRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CollectionService {

    private final CollectionRepository collectionRepository;
    private final CardRepository cardRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<CollectionItemResponse> getUserCollection(Long userId) {
        return collectionRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<CollectionItemResponse> getUserCollectionPaged(Long userId, Pageable pageable) {
        return collectionRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(this::mapToResponse);
    }

    @Transactional
    public CollectionItemResponse addToCollection(Long userId, AddToCollectionRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + userId));

        String cardId = request.resolveCardId();
        if (cardId == null) {
            throw new IllegalArgumentException("cardId or listingId is required");
        }

        Card card = cardRepository.findById(cardId)
                .orElseThrow(() -> new CardNotFoundException(cardId));

        CollectionItem item = collectionRepository.findByUserIdAndCardId(userId, card.getId())
                .map(existing -> {
                    existing.setQuantity(existing.getQuantity() + (request.getQuantity() != null ? request.getQuantity() : 1));
                    if (request.getCondition() != null) {
                        existing.setCondition(request.getCondition());
                    }
                    if (request.getLanguage() != null) {
                        existing.setLanguage(request.getLanguage());
                    }
                    return existing;
                })
                .orElseGet(() -> CollectionItem.builder()
                        .user(user)
                        .card(card)
                        .condition(request.getCondition() != null ? request.getCondition() : "Near Mint")
                        .language(request.getLanguage() != null ? request.getLanguage() : "EN")
                        .quantity(request.getQuantity() != null ? request.getQuantity() : 1)
                        .build()
                );

        CollectionItem saved = collectionRepository.save(item);
        return mapToResponse(saved);
    }

    @Transactional
    public void removeFromCollection(Long userId, Long collectionItemId) {
        CollectionItem item = collectionRepository.findByIdAndUserId(collectionItemId, userId)
                .orElseThrow(() -> new CollectionItemNotFoundException(collectionItemId));

        collectionRepository.delete(item);
    }

    private CollectionItemResponse mapToResponse(CollectionItem item) {
        Card card = item.getCard();
        String imageUrl = card != null ? (card.getImageLarge() != null ? card.getImageLarge() : card.getImageSmall()) : null;

        CardListingDto listingDto = CardListingDto.builder()
                .id(card != null ? card.getId() : null)
                .name(card != null ? card.getName() : "Unknown Card")
                .game("Pokemon")
                .image(imageUrl)
                .set(card != null ? card.getSetName() : null)
                .rarity(card != null ? card.getRarity() : null)
                .cardNumber(card != null ? card.getCardNumber() : null)
                .condition(item.getCondition())
                .status("SHOW")
                .build();

        String formattedDate = item.getCreatedAt() != null ? item.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null;

        return CollectionItemResponse.builder()
                .id(String.valueOf(item.getId()))
                .ownerId(item.getUser() != null ? String.valueOf(item.getUser().getId()) : null)
                .cardId(card != null ? card.getId() : null)
                .cardName(card != null ? card.getName() : "Unknown Card")
                .game("Pokemon")
                .setName(card != null ? card.getSetName() : null)
                .cardNumber(card != null ? card.getCardNumber() : null)
                .rarity(card != null ? card.getRarity() : null)
                .imageUrl(imageUrl)
                .condition(item.getCondition())
                .language(item.getLanguage())
                .quantity(item.getQuantity())
                .createdAt(formattedDate)
                .acquiredAt(formattedDate)
                .listing(listingDto)
                .build();
    }
}

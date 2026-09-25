package com.mythix.tcg.collection.application;

import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.collection.api.CollectionDto.AddToCollectionRequest;
import com.mythix.tcg.collection.api.CollectionDto.CardListingDto;
import com.mythix.tcg.collection.api.CollectionDto.CollectionItemResponse;
import com.mythix.tcg.collection.domain.CollectionItem;
import com.mythix.tcg.collection.persistence.CollectionRepository;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class CollectionService {

    private final CollectionRepository collectionRepository;
    private final CardRepository cardRepository;
    private final ProductRepository productRepository;
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
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new CollectionUserNotFoundException(userId));

        Card card = resolveCard(request);
        int quantity = request.getQuantity() != null ? request.getQuantity() : 1;
        String condition = normalizeCondition(request.getCondition());
        String language = normalizeLanguage(request.getLanguage());

        CollectionItem item = collectionRepository.findByUserIdAndCardIdAndConditionAndLanguage(
                    userId, card.getId(), condition, language)
                .map(existing -> {
                    try {
                        existing.setQuantity(Math.addExact(existing.getQuantity(), quantity));
                    } catch (ArithmeticException exception) {
                        throw new CollectionValidationException("quantity", "quantity is too large");
                    }
                    return existing;
                })
                .orElseGet(() -> CollectionItem.builder()
                        .user(user)
                        .card(card)
                        .condition(condition)
                        .language(language)
                        .quantity(quantity)
                        .build()
                );

        CollectionItem saved = collectionRepository.save(item);
        return mapToResponse(saved);
    }

    private Card resolveCard(AddToCollectionRequest request) {
        if (request.getCardId() != null && !request.getCardId().isBlank()) {
            String cardId = request.getCardId().trim();
            return cardRepository.findById(cardId)
                    .orElseThrow(() -> new CardNotFoundException(cardId));
        }

        if (request.getListingId() == null || request.getListingId().isBlank()) {
            throw new CollectionValidationException("cardId", "cardId or listingId is required");
        }

        final long listingId;
        try {
            listingId = Long.parseLong(request.getListingId().trim());
            if (listingId <= 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            throw new CollectionValidationException("listingId", "listingId must be a positive number");
        }

        Product product = productRepository.findById(listingId)
                .orElseThrow(() -> new CollectionListingNotFoundException(listingId));
        if (product.getCard() == null) {
            throw new CardNotFoundException("listing " + listingId);
        }
        return product.getCard();
    }

    @Transactional
    public void removeFromCollection(Long userId, Long collectionItemId) {
        CollectionItem item = collectionRepository.findByIdAndUserId(collectionItemId, userId)
                .orElseThrow(() -> new CollectionItemNotFoundException(collectionItemId));

        collectionRepository.delete(item);
    }

    CollectionItemResponse mapToResponse(CollectionItem item) {
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

    private String normalizeCondition(String condition) {
        String value = condition == null ? "" : condition.trim();
        if (value.isEmpty()) {
            return "NM";
        }
        return switch (value.toUpperCase(Locale.ROOT).replace('-', ' ').replace('_', ' ')) {
            case "NM", "NEAR MINT" -> "NM";
            case "LP", "LIGHTLY PLAYED" -> "LP";
            case "MP", "MODERATELY PLAYED" -> "MP";
            case "HP", "HEAVILY PLAYED" -> "HP";
            case "DMG", "DAMAGED" -> "DMG";
            default -> throw new CollectionValidationException(
                    "condition", "Condition must be NM, LP, MP, HP, or DMG");
        };
    }

    private String normalizeLanguage(String language) {
        String value = language == null ? "EN" : language.trim().toUpperCase(Locale.ROOT);
        if (!List.of("EN", "JP", "TH").contains(value)) {
            throw new CollectionValidationException("language", "Language must be EN, JP, or TH");
        }
        return value;
    }
}

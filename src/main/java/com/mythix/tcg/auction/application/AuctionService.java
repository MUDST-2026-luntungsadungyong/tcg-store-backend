package com.mythix.tcg.auction.application;

import com.mythix.tcg.auction.api.AuctionDtos.*;
import com.mythix.tcg.auction.domain.Auction;
import com.mythix.tcg.auction.domain.AuctionCancellationRequest;
import com.mythix.tcg.auction.domain.AuctionPhoto;
import com.mythix.tcg.auction.persistence.AuctionCancellationRepository;
import com.mythix.tcg.auction.persistence.AuctionEvidenceRepository;
import com.mythix.tcg.auction.persistence.AuctionPhotoRepository;
import com.mythix.tcg.auction.persistence.AuctionRepository;
import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.collection.persistence.CollectionRepository;
import com.mythix.tcg.product.domain.CardCondition;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.seller.application.SellerProductImageValidator;
import com.mythix.tcg.seller.application.SellerRequiredException;
import com.mythix.tcg.seller.persistence.SellerProductRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.mythix.tcg.auction.application.AuctionException.conflict;
import static com.mythix.tcg.auction.application.AuctionException.invalid;
import static com.mythix.tcg.auction.application.AuctionException.missing;
import static com.mythix.tcg.auction.domain.Auction.Status.*;
import static com.mythix.tcg.auction.domain.AuctionPhoto.Side.BACK;
import static com.mythix.tcg.auction.domain.AuctionPhoto.Side.FRONT;

@Service
@RequiredArgsConstructor
public class AuctionService {
    private static final Duration MIN_SCHEDULE_LEAD = Duration.ofMinutes(15);
    private static final Set<Integer> DURATIONS = Set.of(24, 72, 120, 168);
    private static final Set<String> CONDITIONS = Set.of("NM", "LP", "MP", "HP", "DMG");
    private static final Set<String> GRADERS = Set.of("PSA", "BGS", "CGC");
    private static final BigDecimal MONEY_LIMIT = new BigDecimal("10000000000");
    private static final Instant MAX_START = Instant.parse("9999-12-31T00:00:00Z");

    private final AuctionRepository auctions;
    private final AuctionSettlementService settlement;
    private final AuctionPhotoRepository photos;
    private final AuctionCancellationRepository cancellations;
    private final AuctionEvidenceRepository evidence;
    private final ProductRepository products;
    private final SellerProductRepository sellerProducts;
    private final CollectionRepository collections;
    private final UserRepository users;
    private final SellerProductImageValidator imageValidator = new SellerProductImageValidator();

    @Transactional(readOnly = true)
    public List<InventoryItem> inventory(Long sellerId) {
        record CollectionKey(String cardId, String condition, String language) {}
        var collectionKeys = collections.findByUserIdOrderByCreatedAtDesc(sellerId).stream()
            .map(item -> new CollectionKey(item.getCard().getId(), CardCondition.code(item.getCondition()), item.getLanguage()))
            .collect(Collectors.toSet());

        return sellerProducts.findAllBySeller_IdAndStatusNotOrderByUpdatedAtDescIdDesc(sellerId, "DELETED")
            .stream().map(product -> {
                Card card = product.getCard();
                var key = new CollectionKey(card.getId(), CardCondition.code(product.getCondition()), product.getLanguage());
                return new InventoryItem(product.getId(), card.getId(), card.getName(), card.getSetName(),
                    referenceImage(card), card.getCardNumber(), card.getRarity(), product.getCondition(),
                    product.getStatus(), availableForAuction(product), collectionKeys.contains(key));
            }).toList();
    }

    @Transactional(readOnly = true)
    public List<AuctionView> list(Long sellerId) {
        return views(auctions.findAllBySellerIdOrderByUpdatedAtDescIdDesc(sellerId));
    }

    @Transactional(readOnly = true)
    public AuctionView detail(Long sellerId, Long id) {
        return view(ownedAuction(sellerId, id));
    }

    @Transactional
    public AuctionView createDraft(Long sellerId, String key, DraftForm form) {
        String creationKey = bounded(key, 100, "Idempotency key");
        if (creationKey != null) {
            User seller = users.findByIdForUpdate(sellerId).orElseThrow(SellerRequiredException::new);
            requireApproved(seller);
            var existing = auctions.findBySellerIdAndCreationKey(sellerId, creationKey);
            if (existing.isPresent()) {
                return view(existing.get());
            }
        }

        var auction = new Auction();
        auction.setSellerId(sellerId);
        auction.setCreationKey(creationKey);
        auction.setCreatedAt(Instant.now());
        updateDraftFields(auction, form);
        auctions.save(auction);
        updatePhoto(auction.getId(), FRONT, form.getFrontImage(), false);
        updatePhoto(auction.getId(), BACK, form.getBackImage(), false);
        return view(auction);
    }

    @Transactional
    public AuctionView updateDraft(Long sellerId, Long id, DraftForm form) {
        Auction auction = lockedOwnedAuction(sellerId, id);
        requireDraft(auction);
        boolean productChanged = !Objects.equals(auction.getProductId(), form.getProductId());
        updateDraftFields(auction, form);
        updatePhoto(id, FRONT, form.getFrontImage(), productChanged);
        updatePhoto(id, BACK, form.getBackImage(), productChanged);
        return view(auction);
    }

    @Transactional
    public void deleteDraft(Long sellerId, Long id) {
        Auction auction = lockedOwnedAuction(sellerId, id);
        requireDraft(auction);
        // Hibernate-generated test schemas have no database cascade on scalar references.
        photos.deleteAllById(List.of(new AuctionPhoto.Id(id, FRONT), new AuctionPhoto.Id(id, BACK)));
        auctions.delete(auction);
    }

    @Transactional
    public AuctionView publish(Long sellerId, Long id) {
        requireApproved(users.findById(sellerId).orElseThrow(SellerRequiredException::new));
        Auction auction = lockedOwnedAuction(sellerId, id);
        if (auction.getStatus() != DRAFT) {
            return view(auction);
        }

        if (auction.getProductId() == null || auction.getCondition() == null || auction.getStartPrice() == null
            || auction.getStartMode() == null || auction.getDurationHours() == null) {
            throw invalid("Select seller inventory, a condition, a start price, a start mode and a duration");
        }
        validateGrading(auction, true);
        if (photos.findPhotoIds(List.of(id)).size() != 2) {
            throw invalid("Upload an actual front and back card photo before publishing");
        }

        Product product = products.findByIdForUpdate(auction.getProductId())
            .filter(item -> item.getSeller().getId().equals(sellerId))
            .orElseThrow(() -> conflict("The selected seller inventory no longer exists"));
        if (availableForAuction(product) < 1) {
            throw conflict("The selected seller inventory is no longer available");
        }
        if (!auction.getCondition().equals(CardCondition.code(product.getCondition()))) {
            throw conflict("Auction condition must match the selected inventory's current condition");
        }
        // Sample the clock after acquiring stock, which can block behind checkout.
        Instant now = Instant.now();
        Instant start;
        if (auction.getStartMode() == Auction.StartMode.NOW) {
            start = now.truncatedTo(ChronoUnit.SECONDS);
            auction.setStatus(ACTIVE);
        } else {
            start = auction.getStartAt();
            if (start == null || start.isBefore(now.plus(MIN_SCHEDULE_LEAD))) {
                throw invalid("Scheduled start must be at least 15 minutes after the server time");
            }
            auction.setStatus(SCHEDULED);
        }
        auction.setStartAt(start);
        auction.setEndAt(start.plus(Duration.ofHours(auction.getDurationHours())));
        auction.setUpdatedAt(now);
        auction.setStockHeld(true);
        product.setAuctionReserved(product.getAuctionReserved() + 1);
        return view(auction);
    }

    @Transactional
    public AuctionView cancel(Long sellerId, Long id) {
        Auction auction = lockedOwnedAuction(sellerId, id);
        if (auction.getStatus() == CANCELLED && auction.getBidCount() == 0) {
            return view(auction);
        }
        Instant now = Instant.now();
        if (!auction.canCancel(now)) {
            throw conflict("This auction is no longer eligible for cancellation");
        }
        if (auction.getBidCount() > 0) {
            throw conflict("This auction has bids. Submit a cancellation request for review");
        }
        cancelLocked(auction, now);
        return view(auction);
    }

    @Transactional
    public void advanceLifecycle(Long id, Instant now) {
        var candidate = auctions.findByIdForUpdate(id);
        if (candidate.isEmpty()) {
            return;
        }

        Auction auction = candidate.get();
        if ((auction.getStatus() == SCHEDULED || auction.getStatus() == ACTIVE) && !now.isBefore(auction.getEndAt())) {
            settlement.closeAuction(id, now);
        } else if (auction.getStatus() == SCHEDULED && !now.isBefore(auction.getStartAt())) {
            auction.setStatus(ACTIVE);
            auction.setUpdatedAt(now);
        }
    }

    @Transactional(readOnly = true)
    public ImageView photo(Long sellerId, Long id, AuctionPhoto.Side side) {
        ownedAuction(sellerId, id);
        var photo = photos.findById(new AuctionPhoto.Id(id, side))
            .orElseThrow(() -> missing("Auction photo not found"));
        return new ImageView(photo.getContentType(), photo.getContent());
    }

    Auction lockedAuction(Long id) {
        return auctions.findByIdForUpdate(id).orElseThrow(() -> missing("Auction not found"));
    }

    Auction lockedOwnedAuction(Long sellerId, Long id) {
        Auction auction = lockedAuction(id);
        if (!sellerId.equals(auction.getSellerId())) {
            throw missing("Auction not found");
        }
        return auction;
    }

    Auction ownedAuction(Long sellerId, Long id) {
        return auctions.findByIdAndSellerId(id, sellerId).orElseThrow(() -> missing("Auction not found"));
    }

    void cancelLocked(Auction auction, Instant now) {
        releaseStock(auction);
        auction.setStatus(CANCELLED);
        auction.setUpdatedAt(now);
    }

    static CancellationView cancellationView(Auction auction, AuctionCancellationRequest request, User seller,
                                               List<AuctionEvidenceRepository.FileRef> files, boolean admin) {
        String base = admin ? "/api/admin/auction-cancellation-requests/"
            : "/api/seller/auctions/" + auction.getId() + "/cancellation-requests/";
        List<EvidenceView> evidenceViews = files.stream()
            .map(file -> new EvidenceView(file.id(), file.fileName(), base + request.getId() + "/evidence/" + file.id()))
            .toList();
        return new CancellationView(request.getId(), auction.getId(), auction.getStatus(), auction.getCardName(),
            auction.getProductId(), auction.getImageUrl(), auction.getCondition(), auction.getHighestBid(),
            auction.getEndAt(),
            auction.getSellerId(), seller.getFullName(), auction.getBidCount(), request.getReason(), request.getStatus(),
            request.getDecisionReason(), request.getCreatedAt(), request.getReviewedAt(), evidenceViews);
    }

    private void releaseStock(Auction auction) {
        if (!auction.isStockHeld()) {
            return;
        }
        Product product = products.findByIdForUpdate(auction.getProductId())
            .orElseThrow(() -> new IllegalStateException("Auction held stock references missing inventory"));
        if (product.getAuctionReserved() < 1) {
            throw new IllegalStateException("Auction held stock is missing from inventory");
        }
        product.setAuctionReserved(product.getAuctionReserved() - 1);
        auction.setStockHeld(false);
    }

    private void updateDraftFields(Auction auction, DraftForm form) {
        Product product = null;
        if (form.getProductId() != null) {
            product = products.findById(form.getProductId())
                .filter(item -> item.getSeller().getId().equals(auction.getSellerId()))
                .orElseThrow(() -> missing("Seller inventory not found"));
        }
        Card card = product == null ? null : product.getCard();
        auction.setProductId(form.getProductId());
        auction.setCardId(card == null ? null : card.getId());
        auction.setCardName(card == null ? null : card.getName());
        auction.setSetName(card == null ? null : card.getSetName());
        auction.setImageUrl(card == null ? null : referenceImage(card));

        String condition = upper(form.getCondition());
        if (condition != null && !CONDITIONS.contains(condition)) {
            throw invalid("Condition must be NM, LP, MP, HP, or DMG");
        }
        auction.setCondition(condition);
        auction.setGradingCompany(upper(form.getGradingCompany()));
        auction.setGrade(bounded(form.getGrade(), 30, "Grade"));
        auction.setCertNumber(bounded(form.getCertNumber(), 100, "Certificate number"));
        validateGrading(auction, false);

        validatePrices(form.getStartPrice(), form.getReservePrice(), form.getBuyItNowPrice());
        auction.setStartPrice(form.getStartPrice());
        auction.setReservePrice(form.getReservePrice());
        auction.setBuyItNowPrice(form.getBuyItNowPrice());
        auction.setStartMode(form.getStartMode());
        if (form.getStartAt() != null && (form.getStartAt().isBefore(Instant.EPOCH)
            || form.getStartAt().isAfter(MAX_START))) {
            throw invalid("Scheduled start is outside the supported date range");
        }
        auction.setStartAt(form.getStartAt() == null ? null : form.getStartAt().truncatedTo(ChronoUnit.SECONDS));
        if (form.getDurationHours() != null && !DURATIONS.contains(form.getDurationHours())) {
            throw invalid("Duration must be 24 hours, 3 days, 5 days, or 7 days");
        }
        auction.setDurationHours(form.getDurationHours());
        auction.setUpdatedAt(Instant.now());
    }

    private void updatePhoto(Long id, AuctionPhoto.Side side, MultipartFile image, boolean productChanged) {
        var photoId = new AuctionPhoto.Id(id, side);
        if (image != null) {
            var validated = imageValidator.validate(image);
            photos.save(new AuctionPhoto(photoId, validated.contentType(), validated.content()));
        } else if (productChanged) {
            photos.deleteById(photoId);
        }
    }

    private AuctionView view(Auction auction) {
        return views(List.of(auction)).getFirst();
    }

    private List<AuctionView> views(List<Auction> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        List<Long> ids = entries.stream().map(Auction::getId).toList();
        Map<Long, Set<AuctionPhoto.Side>> sides = new HashMap<>();
        for (AuctionPhoto.Id id : photos.findPhotoIds(ids)) {
            sides.computeIfAbsent(id.auctionId(), unused -> EnumSet.noneOf(AuctionPhoto.Side.class)).add(id.side());
        }
        Map<Long, AuctionCancellationRequest> latest = new HashMap<>();
        for (AuctionCancellationRequest request : cancellations.findAllByAuctionIdInOrderByIdDesc(ids)) {
            latest.putIfAbsent(request.getAuctionId(), request);
        }
        Map<Long, List<AuctionEvidenceRepository.FileRef>> files = latest.isEmpty() ? Map.of()
            : evidence.findFileRefs(latest.values().stream().map(AuctionCancellationRequest::getId).toList())
                .stream().collect(Collectors.groupingBy(AuctionEvidenceRepository.FileRef::requestId));
        Map<Long, User> sellers = latest.isEmpty() ? Map.of()
            : users.findAllById(entries.stream().filter(a -> latest.containsKey(a.getId()))
                .map(Auction::getSellerId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));

        Instant now = Instant.now();
        return entries.stream().map(a -> {
            Set<AuctionPhoto.Side> existing = sides.getOrDefault(a.getId(), Set.of());
            String base = "/api/seller/auctions/" + a.getId() + "/photos/";
            AuctionCancellationRequest request = latest.get(a.getId());
            CancellationView cancellation = request == null ? null : cancellationView(a, request,
                sellers.get(a.getSellerId()), files.getOrDefault(request.getId(), List.of()), false);
            return new AuctionView(a.getId(), a.getStatus(), a.getProductId(), a.getCardId(), a.getCardName(),
                a.getSetName(), a.getImageUrl(), a.getCondition(), a.getGradingCompany(), a.getGrade(),
                a.getCertNumber(), a.getStartPrice(), a.getReservePrice(), a.getBuyItNowPrice(), a.getStartMode(),
                a.getStartAt(), a.getEndAt(), a.getDurationHours(), a.getBidCount(), a.getHighestBid(),
                a.getUpdatedAt(), now, existing.contains(FRONT) ? base + "front" : null,
                existing.contains(BACK) ? base + "back" : null, cancellation);
        }).toList();
    }

    private static void validatePrices(BigDecimal start, BigDecimal reserve, BigDecimal buyNow) {
        validateMoney(start, "Start price");
        validateMoney(reserve, "Reserve price");
        validateMoney(buyNow, "Buy-it-now price");
        if (start != null && reserve != null && reserve.compareTo(start) < 0) {
            throw invalid("Reserve price must be at least the start price");
        }
        if (buyNow != null && ((start != null && buyNow.compareTo(start) <= 0)
            || (reserve != null && buyNow.compareTo(reserve) <= 0))) {
            throw invalid("Buy-it-now price must exceed the start and reserve prices");
        }
    }

    private static void validateMoney(BigDecimal value, String label) {
        if (value != null && (value.signum() <= 0 || value.stripTrailingZeros().scale() > 2
            || value.compareTo(MONEY_LIMIT) >= 0)) {
            throw invalid(label + " must be positive, below 10 billion baht, and use at most two decimal places");
        }
    }

    private static void validateGrading(Auction auction, boolean requireComplete) {
        if (auction.getGradingCompany() != null && !GRADERS.contains(auction.getGradingCompany())) {
            throw invalid("Grading company must be PSA, BGS, or CGC");
        }
        if (requireComplete && (auction.getGradingCompany() != null || auction.getGrade() != null
            || auction.getCertNumber() != null) && (auction.getGradingCompany() == null || auction.getGrade() == null
            || auction.getCertNumber() == null)) {
            throw invalid("Enter the grading company, grade and certificate number together");
        }
    }

    private static int availableForAuction(Product product) {
        return "OUT_OF_STOCK".equalsIgnoreCase(product.getStatus()) ? 0 : product.availableStock();
    }

    private static void requireDraft(Auction auction) {
        if (auction.getStatus() != DRAFT) {
            throw conflict("Only a draft can be edited or deleted");
        }
    }

    private static void requireApproved(User seller) {
        if (!"APPROVED".equalsIgnoreCase(seller.getSellerStatus())) {
            throw new SellerRequiredException();
        }
    }

    static String bounded(String value, int limit, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > limit) {
            throw invalid(label + " must not exceed " + limit + " characters");
        }
        return trimmed;
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String referenceImage(Card card) {
        return card.getImageLarge() != null ? card.getImageLarge() : card.getImageSmall();
    }
}

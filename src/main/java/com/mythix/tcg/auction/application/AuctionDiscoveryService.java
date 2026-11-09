package com.mythix.tcg.auction.application;

import com.mythix.tcg.auction.api.AuctionExtendedDtos.*;
import com.mythix.tcg.auction.api.AuctionDtos;
import com.mythix.tcg.auction.domain.Auction;

import com.mythix.tcg.auction.domain.AuctionPhoto;

import com.mythix.tcg.auction.persistence.AuctionPhotoRepository;
import com.mythix.tcg.auction.persistence.AuctionRepository;
import com.mythix.tcg.auction.persistence.AuctionWatchlistRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static com.mythix.tcg.auction.application.AuctionException.notFound;
import static com.mythix.tcg.auction.domain.AuctionPhoto.Side.BACK;
import static com.mythix.tcg.auction.domain.AuctionPhoto.Side.FRONT;

@Service
@RequiredArgsConstructor
public class AuctionDiscoveryService {

    private final AuctionRepository auctionRepository;

    private final AuctionPhotoRepository photoRepository;
    private final AuctionWatchlistRepository watchlistRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public Page<AuctionSummaryView> searchAuctions(
            String keyword,
            String tab,
            String condition,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            Pageable pageable,
            Long currentUserId
    ) {
        Instant now = Instant.now();

        Specification<Auction> spec = (root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();

            // Default only show ACTIVE auctions for buyer hub unless specified
            predicates.add(cb.equal(root.get("status"), Auction.Status.ACTIVE));
            predicates.add(cb.greaterThan(root.get("endAt"), now));

            if (keyword != null && !keyword.isBlank()) {
                String pattern = "%" + keyword.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("cardName")), pattern),
                    cb.like(cb.lower(root.get("setName")), pattern)
                ));
            }

            if (condition != null && !condition.isBlank() && !"ALL".equalsIgnoreCase(condition)) {
                predicates.add(cb.equal(root.get("condition"), condition.trim().toUpperCase()));
            }

            if (minPrice != null && minPrice.compareTo(BigDecimal.ZERO) > 0) {
                predicates.add(cb.greaterThanOrEqualTo(
                    cb.coalesce(root.get("highestBid"), root.get("startPrice")), minPrice));
            }

            if (maxPrice != null && maxPrice.compareTo(BigDecimal.ZERO) > 0) {
                predicates.add(cb.lessThanOrEqualTo(
                    cb.coalesce(root.get("highestBid"), root.get("startPrice")), maxPrice));
            }

            if ("ending_soon".equalsIgnoreCase(tab)) {
                predicates.add(cb.lessThanOrEqualTo(root.get("endAt"), now.plus(java.time.Duration.ofHours(1))));
            }
            if ("buy_it_now".equalsIgnoreCase(tab)) {
                predicates.add(cb.isNotNull(root.get("buyItNowPrice")));
                predicates.add(cb.or(cb.isNull(root.get("highestBid")),
                    cb.greaterThan(root.<BigDecimal>get("buyItNowPrice"), root.<BigDecimal>get("highestBid"))));
            } else if ("no_reserve".equalsIgnoreCase(tab)) {
                predicates.add(cb.isNull(root.get("reservePrice")));
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        Page<Auction> page = auctionRepository.findAll(spec, pageable);
        if (page.isEmpty()) {
            return Page.empty(pageable);
        }

        List<Long> auctionIds = page.getContent().stream().map(Auction::getId).toList();
        Map<Long, Set<AuctionPhoto.Side>> photoSides = loadPhotoSides(auctionIds);

        Set<Long> sellerIds = new HashSet<>();
        for (Auction a : page.getContent()) {
            sellerIds.add(a.getSellerId());
        }
        Map<Long, User> sellers = new HashMap<>();
        for (User u : userRepository.findAllById(sellerIds)) {
            sellers.put(u.getId(), u);
        }

        Set<Long> watchedIds = new HashSet<>();
        if (currentUserId != null) {
            for (Long aId : auctionIds) {
                if (watchlistRepository.existsByUserIdAndAuctionId(currentUserId, aId)) {
                    watchedIds.add(aId);
                }
            }
        }

        List<AuctionSummaryView> views = page.getContent().stream().map(a -> {
            Set<AuctionPhoto.Side> sides = photoSides.getOrDefault(a.getId(), Set.of());
            String front = sides.contains(FRONT) ? "/api/auctions/" + a.getId() + "/photos/front" : null;
            String back = sides.contains(BACK) ? "/api/auctions/" + a.getId() + "/photos/back" : null;
            User seller = sellers.get(a.getSellerId());
            String sellerName = seller != null && seller.getFullName() != null ? seller.getFullName() : "Seller";

            BigDecimal currentPrice = a.getHighestBid() != null ? a.getHighestBid() : a.getStartPrice();

            return new AuctionSummaryView(
                a.getId(),
                a.getStatus(),
                a.getSellerId(),
                sellerName,
                a.getCardId(),
                a.getCardName(),
                a.getSetName(),
                a.getImageUrl(),
                a.getCondition(),
                a.getQuantity(),
                a.getLanguage(),
                a.getGradingCompany(),
                a.getGrade(),
                a.getCertNumber(),
                a.getStartPrice(),
                currentPrice,
                a.getBidCount(),
                a.getBuyItNowPrice(),
                reserveMet(a),
                a.getStartAt(),
                a.getEndAt(),
                now,
                watchedIds.contains(a.getId()),
                front,
                back
            );
        }).toList();

        return new PageImpl<>(views, pageable, page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public BuyerAuctionDetailView getBuyerAuctionDetail(Long auctionId, Long currentUserId) {
        Auction auction = publicAuction(auctionId);

        Instant now = Instant.now();
        User seller = userRepository.findById(auction.getSellerId()).orElse(null);
        String sellerName = seller != null && seller.getFullName() != null ? seller.getFullName() : "Seller";

        long completedAuctions = auctionRepository.countCompletedBySellerId(auction.getSellerId());
        Double sellerRating = null;

        BigDecimal currentPrice = auction.getHighestBid() != null ? auction.getHighestBid() : auction.getStartPrice();
        BigDecimal nextMinBid = AuctionBidService.getNextMinimumBid(auction);
        BigDecimal increment = AuctionBidService.calculateIncrement(currentPrice);

        boolean isWatching = currentUserId != null && watchlistRepository.existsByUserIdAndAuctionId(currentUserId, auctionId);

        boolean isHighBidder = currentUserId != null && currentUserId.equals(auction.getLeadingBidderId());

        Map<Long, Set<AuctionPhoto.Side>> photoSides = loadPhotoSides(List.of(auctionId));
        Set<AuctionPhoto.Side> sides = photoSides.getOrDefault(auctionId, Set.of());
        String front = sides.contains(FRONT) ? "/api/auctions/" + auctionId + "/photos/front" : null;
        String back = sides.contains(BACK) ? "/api/auctions/" + auctionId + "/photos/back" : null;

        String certUrl = buildCertificationUrl(auction.getGradingCompany(), auction.getCertNumber());

        return new BuyerAuctionDetailView(
            auction.getId(),
            auction.getStatus(),
            auction.getSellerId(),
            sellerName,
            sellerRating,
            completedAuctions,
            auction.getProductId(),
            auction.getCardId(),
            auction.getCardName(),
            auction.getSetName(),
            auction.getImageUrl(),
            auction.getCondition(),
            auction.getQuantity(),
            auction.getLanguage(),
            auction.getGradingCompany(),
            auction.getGrade(),
            auction.getCertNumber(),
            certUrl,
            auction.getStartPrice(),
            currentPrice,
            nextMinBid,
            increment,
            reserveMet(auction),
            auction.getBuyItNowPrice(),
            auction.getStartAt(),
            auction.getEndAt(),
            now,
            auction.getBidCount(),
            isWatching,
            isHighBidder,
            front,
            back
        );
    }

    private Auction publicAuction(Long id) {
        return auctionRepository.findById(id)
            .filter(auction -> auction.getStatus() != Auction.Status.DRAFT)
            .orElseThrow(() -> notFound("Auction not found"));
    }

    @Transactional(readOnly = true)
    public AuctionDtos.ImageView photo(Long id, AuctionPhoto.Side side) {
        publicAuction(id);
        var photo = photoRepository.findById(new AuctionPhoto.Id(id, side))
            .orElseThrow(() -> notFound("Auction photo not found"));
        return new AuctionDtos.ImageView(photo.getContentType(), photo.getContent());
    }

    private boolean reserveMet(Auction auction) {
        return auction.getReservePrice() == null || auction.isReserveMet();
    }

    public static String buildCertificationUrl(String gradingCompany, String certNumber) {
        if (gradingCompany == null || certNumber == null || certNumber.isBlank()) {
            return null;
        }
        String cleanCert = certNumber.trim();
        return switch (gradingCompany.toUpperCase(Locale.ROOT)) {
            case "PSA" -> "https://www.psacard.com/cert/" + cleanCert;
            case "BGS" -> "https://www.beckett.com/grading/card-lookup?item_type=BGS&item_id=" + cleanCert;
            case "CGC" -> "https://www.cgccards.com/certlookup/" + cleanCert;
            default -> null;
        };
    }

    private Map<Long, Set<AuctionPhoto.Side>> loadPhotoSides(List<Long> auctionIds) {
        Map<Long, Set<AuctionPhoto.Side>> sides = new HashMap<>();
        for (AuctionPhoto.Id id : photoRepository.findPhotoIds(auctionIds)) {
            sides.computeIfAbsent(id.auctionId(), unused -> EnumSet.noneOf(AuctionPhoto.Side.class)).add(id.side());
        }
        return sides;
    }
}

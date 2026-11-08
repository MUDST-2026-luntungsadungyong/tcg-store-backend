package com.mythix.tcg.auction.api;

import com.mythix.tcg.auction.api.AuctionDtos.*;
import com.mythix.tcg.auction.application.AuctionCancellationService;
import com.mythix.tcg.auction.application.AuctionException;
import com.mythix.tcg.auction.application.AuctionService;
import com.mythix.tcg.auction.domain.AuctionPhoto;
import com.mythix.tcg.auth.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/seller/auctions")
@RequiredArgsConstructor
public class SellerAuctionController {
    private final AuctionService auctions;
    private final AuctionCancellationService cancellations;

    @GetMapping
    public List<AuctionView> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return auctions.list(user.userId());
    }

    @GetMapping("/inventory")
    public List<InventoryItem> inventory(@AuthenticationPrincipal AuthenticatedUser user) {
        return auctions.inventory(user.userId());
    }

    @GetMapping("/{id}")
    public AuctionView detail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id) {
        return auctions.detail(user.userId(), id);
    }

    @PostMapping(path = "/drafts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AuctionView createDraft(@AuthenticationPrincipal AuthenticatedUser user,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                   @ModelAttribute DraftForm form) {
        return auctions.createDraft(user.userId(), key, form);
    }

    @PutMapping(path = "/{id}/draft", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AuctionView updateDraft(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id,
                                   @ModelAttribute DraftForm form) {
        return auctions.updateDraft(user.userId(), id, form);
    }

    @DeleteMapping("/{id}/draft")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDraft(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id) {
        auctions.deleteDraft(user.userId(), id);
    }

    @PostMapping("/{id}/publish")
    public AuctionView publish(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id) {
        return auctions.publish(user.userId(), id);
    }

    @PostMapping("/{id}/cancel")
    public AuctionView cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id) {
        return auctions.cancel(user.userId(), id);
    }

    @PostMapping(path = "/{id}/cancellation-requests", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CancellationView requestCancellation(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id,
                                                 @RequestParam String reason,
                                                 @RequestParam(required = false) List<MultipartFile> evidence) {
        return cancellations.submit(user.userId(), id, reason, evidence);
    }

    @GetMapping("/{id}/photos/{side}")
    public ResponseEntity<byte[]> photo(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id,
                                        @PathVariable String side) {
        AuctionPhoto.Side photoSide;
        try {
            photoSide = AuctionPhoto.Side.valueOf(side.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw AuctionException.missing("Auction photo not found");
        }
        return imageResponse(auctions.photo(user.userId(), id, photoSide));
    }

    @GetMapping("/{id}/cancellation-requests/{requestId}/evidence/{fileId}")
    public ResponseEntity<byte[]> evidence(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id,
                                           @PathVariable Long requestId, @PathVariable Long fileId) {
        return imageResponse(cancellations.sellerEvidence(user.userId(), id, requestId, fileId));
    }

    static ResponseEntity<byte[]> imageResponse(ImageView image) {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(image.contentType()))
            .cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .body(image.content());
    }
}

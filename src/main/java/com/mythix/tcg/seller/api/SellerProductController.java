package com.mythix.tcg.seller.api;

import com.mythix.tcg.auth.security.AuthenticatedUser;
import com.mythix.tcg.seller.application.SellerProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping({"/api/seller/products", "/api/seller/listings"})
@RequiredArgsConstructor
public class SellerProductController {

    private final SellerProductService service;

    @GetMapping
    public List<SellerProductResponse> findAll(
        @AuthenticationPrincipal AuthenticatedUser user,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String game,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size
    ) {
        return service.findAll(user.userId(), search, game, status, page, size);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SellerProductResponse createJson(
        @AuthenticationPrincipal AuthenticatedUser user,
        @Valid @RequestBody SellerProductRequest request
    ) {
        return service.create(user.userId(), request, null);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SellerProductResponse createMultipart(
        @AuthenticationPrincipal AuthenticatedUser user,
        @Valid @ModelAttribute SellerProductMultipartRequest request
    ) {
        return service.create(user.userId(), request.toRequest(), request.getImage());
    }

    @PutMapping(path = "/{productId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SellerProductResponse updateJson(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable Long productId,
        @Valid @RequestBody SellerProductRequest request
    ) {
        return service.update(user.userId(), productId, request, null);
    }

    @PutMapping(path = "/{productId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SellerProductResponse updateMultipart(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable Long productId,
        @Valid @ModelAttribute SellerProductMultipartRequest request
    ) {
        return service.update(user.userId(), productId, request.toRequest(), request.getImage());
    }

    @PatchMapping({"/{productId}/status", "/{productId}/visibility"})
    public ResponseEntity<Void> changeStatus(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable Long productId,
        @Valid @RequestBody SellerProductStatusRequest request
    ) {
        service.changeStatus(user.userId(), productId, request.status());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> delete(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable Long productId
    ) {
        service.delete(user.userId(), productId);
        return ResponseEntity.noContent().build();
    }
}

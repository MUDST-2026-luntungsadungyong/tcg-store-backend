package com.mythix.tcg.cart.api;

import com.mythix.tcg.auth.security.AuthenticatedUser;
import com.mythix.tcg.cart.application.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping
    public CartResponse getCart(@AuthenticationPrincipal AuthenticatedUser user) {
        return cartService.getCart(user.userId());
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public CartResponse addItem(
        @AuthenticationPrincipal AuthenticatedUser user,
        @Valid @RequestBody AddCartItemRequest request
    ) {
        return cartService.addItem(user.userId(), request);
    }

    @PatchMapping("/items/{listingId}")
    public CartResponse updateItem(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable String listingId,
        @Valid @RequestBody UpdateCartItemRequest request
    ) {
        return cartService.updateItem(user.userId(), listingId, request);
    }

    @DeleteMapping("/items/{listingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItem(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable String listingId
    ) {
        cartService.deleteItem(user.userId(), listingId);
    }
}

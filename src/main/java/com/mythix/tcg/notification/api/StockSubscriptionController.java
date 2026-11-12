package com.mythix.tcg.notification.api;

import com.mythix.tcg.auth.security.AuthenticatedUser;
import com.mythix.tcg.notification.application.StockSubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/stock-subscriptions/{globalCardId}")
@RequiredArgsConstructor
public class StockSubscriptionController {
    private final StockSubscriptionService service;

    @GetMapping
    public StockSubscriptionResponse get(@AuthenticationPrincipal AuthenticatedUser user,
                                         @PathVariable String globalCardId) {
        return service.get(user.userId(), globalCardId);
    }

    @PutMapping
    public StockSubscriptionResponse put(@AuthenticationPrincipal AuthenticatedUser user,
                                         @PathVariable String globalCardId) {
        return service.subscribe(user.userId(), globalCardId);
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user,
                                       @PathVariable String globalCardId) {
        service.unsubscribe(user.userId(), globalCardId);
        return ResponseEntity.noContent().build();
    }
}

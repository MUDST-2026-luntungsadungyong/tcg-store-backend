package com.mythix.tcg.notification.api;

import com.mythix.tcg.auth.security.AuthenticatedUser;
import com.mythix.tcg.notification.application.NotificationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Validated
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<Page<NotificationResponse>> getNotifications(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var pageable = PageRequest.of(page, size, Sort.by(
            Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return ResponseEntity.ok(notificationService.getNotifications(user.userId(), pageable));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCountResponse> getUnreadCount(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(new UnreadCountResponse(notificationService.getUnreadCount(user.userId())));
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<NotificationResponse> markRead(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable @Min(1) Long notificationId) {
        return ResponseEntity.ok(notificationService.markRead(user.userId(), notificationId));
    }

    @DeleteMapping("/{notificationId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable @Min(1) Long notificationId) {
        notificationService.delete(user.userId(), notificationId);
        return ResponseEntity.noContent().build();
    }
}

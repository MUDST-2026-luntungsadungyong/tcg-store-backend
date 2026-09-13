package com.mythix.tcg.collection.api;

import com.mythix.tcg.collection.api.PublicCollectionDto.LeaderboardResponse;
import com.mythix.tcg.collection.api.PublicCollectionDto.PublicCollectionResponse;
import com.mythix.tcg.collection.application.PublicCollectionService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/collections")
@RequiredArgsConstructor
@Validated
public class PublicCollectionController {

    private final PublicCollectionService publicCollectionService;

    @GetMapping("/leaderboard")
    public ResponseEntity<LeaderboardResponse> getLeaderboard(
            @RequestParam(defaultValue = "10") @Min(1) @Max(10) int limit) {
        return ResponseEntity.ok(publicCollectionService.getLeaderboard(PageRequest.of(0, limit)));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<PublicCollectionResponse> getPublicCollection(
            @PathVariable @Positive Long userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(publicCollectionService.getPublicCollection(userId, PageRequest.of(page, size)));
    }
}

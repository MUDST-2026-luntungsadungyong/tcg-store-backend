package com.mythix.tcg.common.health;

import com.mythix.tcg.catalog.persistence.CardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final CardRepository cardRepository;

    @GetMapping
    public ResponseEntity<Map<String, Object>> checkHealth() {
        long totalCards = cardRepository.count();
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "message", "Mythix TCG Backend is running healthy!",
            "timestamp", LocalDateTime.now().toString(),
            "database", "Connected",
            "total_cards_in_db", totalCards
        ));
    }
}

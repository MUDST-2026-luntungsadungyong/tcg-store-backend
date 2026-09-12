package com.mythix.tcg.collection.api;

import com.mythix.tcg.auth.security.AuthenticatedUser;
import com.mythix.tcg.collection.api.CollectionDto.AddToCollectionRequest;
import com.mythix.tcg.collection.api.CollectionDto.CollectionItemResponse;
import com.mythix.tcg.collection.api.CollectionDto.CollectionResponse;
import com.mythix.tcg.collection.api.CollectionDto.PageMetadata;
import com.mythix.tcg.collection.application.CollectionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/collection")
@RequiredArgsConstructor
@Validated
public class CollectionController {

    private final CollectionService collectionService;

    @GetMapping
    public ResponseEntity<CollectionResponse> getCollection(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) @Min(0) Integer page,
            @RequestParam(required = false) @Min(1) @Max(100) Integer size) {

        if (page != null) {
            int pageSize = (size != null) ? size : 20;
            Pageable pageable = PageRequest.of(page, pageSize);
            Page<CollectionItemResponse> response = collectionService.getUserCollectionPaged(user.userId(), pageable);
            return ResponseEntity.ok(CollectionResponse.builder()
                    .items(response.getContent())
                    .page(PageMetadata.builder()
                            .page(response.getNumber())
                            .size(response.getSize())
                            .totalElements(response.getTotalElements())
                            .totalPages(response.getTotalPages())
                            .build())
                    .build());
        }

        List<CollectionItemResponse> response = collectionService.getUserCollection(user.userId());
        return ResponseEntity.ok(CollectionResponse.builder()
                .items(response)
                .page(PageMetadata.builder()
                        .page(0)
                        .size(response.size())
                        .totalElements(response.size())
                        .totalPages(response.isEmpty() ? 0 : 1)
                        .build())
                .build());
    }

    @PostMapping
    public ResponseEntity<CollectionItemResponse> addToCollection(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody AddToCollectionRequest request) {

        CollectionItemResponse response = collectionService.addToCollection(user.userId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> removeFromCollection(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long id) {

        collectionService.removeFromCollection(user.userId(), id);
        return ResponseEntity.noContent().build();
    }
}

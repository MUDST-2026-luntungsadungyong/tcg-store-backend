package com.mythix.tcg.seller.persistence;

import com.mythix.tcg.seller.domain.SellerProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.Optional;
import java.util.List;

public interface SellerProfileRepository extends JpaRepository<SellerProfile, Long> {
    boolean existsByUserId(Long userId);
    Optional<SellerProfile> findByUserId(Long userId);

    @EntityGraph(attributePaths = "user")
    List<SellerProfile> findAllByUserSellerStatusOrderByRequestedAtAsc(String sellerStatus);
}

package com.mythix.tcg.seller.persistence;

import com.mythix.tcg.seller.domain.SellerProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerProfileRepository extends JpaRepository<SellerProfile, Long> {
    boolean existsByUserId(Long userId);
}

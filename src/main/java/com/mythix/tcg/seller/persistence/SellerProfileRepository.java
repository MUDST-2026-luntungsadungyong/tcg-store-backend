package com.mythix.tcg.seller.persistence;

import com.mythix.tcg.seller.domain.SellerProfile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.Collection;
import java.util.List;

public interface SellerProfileRepository extends JpaRepository<SellerProfile, Long> {
    boolean existsByUserId(Long userId);
    Optional<SellerProfile> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "user")
    @Query("select sp from SellerProfile sp where sp.id = :id")
    Optional<SellerProfile> findByIdForUpdate(@Param("id") Long id);

    @EntityGraph(attributePaths = "user")
    List<SellerProfile> findAllByUserSellerStatusOrderByRequestedAtAsc(String sellerStatus);
    List<SellerProfile> findByUserIdIn(Collection<Long> userIds);
}

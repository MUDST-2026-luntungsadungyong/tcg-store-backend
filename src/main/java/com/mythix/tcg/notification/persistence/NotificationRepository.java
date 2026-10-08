package com.mythix.tcg.notification.persistence;

import com.mythix.tcg.notification.domain.UserNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface NotificationRepository extends JpaRepository<UserNotification, Long> {
    Page<UserNotification> findAllByRecipient_IdOrderByCreatedAtDescIdDesc(Long recipientId, Pageable pageable);

    Optional<UserNotification> findByIdAndRecipient_Id(Long id, Long recipientId);

    long countByRecipient_IdAndReadAtIsNull(Long recipientId);

    @Modifying
    @Query("update UserNotification n set n.readAt = CURRENT_TIMESTAMP " +
        "where n.sellerProfile.id = :sellerProfileId " +
        "and n.type = 'SELLER_APPLICATION_SUBMITTED' and n.readAt is null")
    int resolveSellerApplicationReviewNotifications(@Param("sellerProfileId") Long sellerProfileId);

    long deleteByIdAndRecipient_Id(Long id, Long recipientId);
}

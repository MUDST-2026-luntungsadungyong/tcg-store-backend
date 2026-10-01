package com.mythix.tcg.notification.persistence;

import com.mythix.tcg.notification.domain.UserNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotificationRepository extends JpaRepository<UserNotification, Long> {
    Page<UserNotification> findAllByRecipient_IdOrderByCreatedAtDescIdDesc(Long recipientId, Pageable pageable);

    Optional<UserNotification> findByIdAndRecipient_Id(Long id, Long recipientId);

    long deleteByIdAndRecipient_Id(Long id, Long recipientId);
}

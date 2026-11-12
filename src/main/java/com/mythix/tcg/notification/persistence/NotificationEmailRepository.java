package com.mythix.tcg.notification.persistence;

import com.mythix.tcg.notification.domain.NotificationEmail;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationEmailRepository extends JpaRepository<NotificationEmail, Long> {
    @Query("select e from NotificationEmail e where e.sentAt is null and e.cancelledAt is null and e.failedAt is null and e.nextAttemptAt <= :now and (e.claimedUntil is null or e.claimedUntil < :now) order by e.nextAttemptAt asc, e.id asc")
    List<NotificationEmail> findDue(@Param("now") LocalDateTime now, Pageable pageable);

    @Modifying
    @Transactional
    @Query("update NotificationEmail e set e.claimedUntil = :lease, e.attempts = e.attempts + 1 where e.id = :id and e.sentAt is null and e.cancelledAt is null and e.failedAt is null and e.nextAttemptAt <= :now and (e.claimedUntil is null or e.claimedUntil < :now)")
    int claim(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("lease") LocalDateTime lease);

    @Modifying
    @Transactional
    @Query("update NotificationEmail e set e.sentAt = :now, e.claimedUntil = null, e.lastError = null, e.body = case when e.sensitive = true then '[removed]' else e.body end where e.id = :id")
    int markSent(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("update NotificationEmail e set e.claimedUntil = null, e.nextAttemptAt = :next, e.lastError = :error where e.id = :id")
    int reschedule(@Param("id") Long id, @Param("next") LocalDateTime next, @Param("error") String error);

    @Modifying
    @Transactional
    @Query("update NotificationEmail e set e.failedAt = :now, e.claimedUntil = null, e.failureReason = :reason, e.lastError = :reason where e.id = :id")
    int markFailed(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("reason") String reason);
}

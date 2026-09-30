package com.mythix.tcg.admin.persistence;

import com.mythix.tcg.admin.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    @EntityGraph(attributePaths = {"actor"})
    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = {"actor"})
    Page<AuditLog> findByActionOrderByCreatedAtDesc(String action, Pageable pageable);

    @EntityGraph(attributePaths = {"actor"})
    @Query("""
        select a from AuditLog a left join a.actor actor
        where (:query = ''
          or (:searchBy = 'ALL' and (
                lower(a.action) like lower(concat('%', :query, '%'))
                or lower(a.targetType) like lower(concat('%', :query, '%'))
                or lower(a.targetId) like lower(concat('%', :query, '%'))
                or lower(a.details) like lower(concat('%', :query, '%'))
                or (actor is not null and (lower(actor.fullName) like lower(concat('%', :query, '%')) or lower(actor.email) like lower(concat('%', :query, '%'))))
             ))
          or (:searchBy = 'ACTOR' and (
                (actor is not null and (lower(actor.fullName) like lower(concat('%', :query, '%')) or lower(actor.email) like lower(concat('%', :query, '%'))))
                or (actor is null and lower('System') like lower(concat('%', :query, '%')))
             ))
          or (:searchBy = 'TARGET' and (lower(a.targetType) like lower(concat('%', :query, '%')) or lower(a.targetId) like lower(concat('%', :query, '%'))))
          or (:searchBy = 'DETAILS' and lower(a.details) like lower(concat('%', :query, '%')))
        )
        and (:actionsEmpty = 'Y' or a.action in :actions)
        and (cast(:fromDate as localdatetime) is null or a.createdAt >= :fromDate)
        and (cast(:toDate as localdatetime) is null or a.createdAt < :toDate)
        order by a.createdAt desc
        """)
    Page<AuditLog> searchAuditLogs(
        @Param("query") String query,
        @Param("searchBy") String searchBy,
        @Param("actions") List<String> actions,
        @Param("actionsEmpty") String actionsEmpty,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate,
        Pageable pageable
    );
}

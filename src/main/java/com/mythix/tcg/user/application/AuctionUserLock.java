package com.mythix.tcg.user.application;

import com.mythix.tcg.user.domain.User;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Serializes auction penalties without blocking foreign-key references to the account. */
@Component
@RequiredArgsConstructor
public class AuctionUserLock {
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;

    public Optional<User> lock(Long id) {
        Boolean postgres = jdbc.execute((ConnectionCallback<Boolean>) connection ->
            connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL"));
        String lock = Boolean.TRUE.equals(postgres) ? " for no key update" : " for update";
        var rows = entityManager.createNativeQuery("select id from users where id = :id" + lock)
            .setParameter("id", id).getResultList();
        if (rows.isEmpty()) return Optional.empty();
        User user = entityManager.find(User.class, id);
        entityManager.refresh(user);
        return Optional.of(user);
    }
}

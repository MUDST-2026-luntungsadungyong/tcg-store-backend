package com.mythix.tcg.auth.application;

import com.mythix.tcg.auth.domain.PasswordResetToken;
import com.mythix.tcg.auth.persistence.PasswordResetTokenRepository;
import com.mythix.tcg.notification.application.NotificationEmailService;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PasswordResetService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordEncoder passwords;
    private final NotificationEmailService emails;

    @Value("${app.mail.base-url:http://localhost:5173}")
    private String baseUrl;

    @Transactional
    public void requestReset(String email) {
        users.findByEmailForUpdate(email.trim().toLowerCase(Locale.ROOT)).ifPresent(user -> {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            PasswordResetToken token = tokens.findByUserId(user.getId()).orElseGet(PasswordResetToken::new);
            if (token.getCreatedAt() != null && token.getCreatedAt().plusMinutes(1).isAfter(now)) return;
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            token.setUserId(user.getId());
            token.setTokenHash(hash(secret));
            token.setCreatedAt(now);
            token.setExpiresAt(now.plusMinutes(30));
            tokens.save(token);
            String url = baseUrl.replaceAll("/+$", "") + "/reset-password?token=" + secret;
            emails.enqueueSensitive(user.getEmail(), "Mythix: Reset your password",
                "Use this link to reset your password within 30 minutes:\n\n" + url
                    + "\n\nIf you did not request this, you can ignore this email.", token.getExpiresAt());
        });
    }

    @Transactional
    public void resetPassword(String secret, String newPassword) {
        String hash = hash(secret);
        User user = users.findByResetTokenHashForUpdate(hash).orElseThrow(PasswordResetService::invalidToken);
        // The account lock serializes reset requests and consumption; re-read after acquiring it.
        PasswordResetToken token = tokens.findByUserId(user.getId()).orElseThrow(PasswordResetService::invalidToken);
        if (!MessageDigest.isEqual(token.getTokenHash().getBytes(StandardCharsets.US_ASCII), hash.getBytes(StandardCharsets.US_ASCII))
                || !token.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) throw invalidToken();
        user.setPassword(passwords.encode(newPassword));
        user.setAuthVersion(user.getAuthVersion() + 1);
        tokens.delete(token);
        emails.enqueueSensitive(user.getEmail(), "Mythix: Your password was changed",
            "Your Mythix password was changed. If this was not you, contact support immediately.",
            LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
    }

    private static ResponseStatusException invalidToken() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired reset link");
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

package com.mythix.tcg.notification.application;

import com.mythix.tcg.auth.security.JwtProperties;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Keeps usable reset links out of the outbox at rest. Rotating the JWT secret invalidates queued mail. */
@Component
public class SensitiveMailCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SensitiveMailCipher(JwtProperties properties) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                ("mythix-sensitive-mail-v1:" + properties.secret()).getBytes(StandardCharsets.UTF_8));
            key = new SecretKeySpec(digest, "AES");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, combined, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) { throw new IllegalStateException("Cannot protect email", e); }
    }

    public String decrypt(String ciphertext) {
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(combined, 12)));
            return new String(cipher.doFinal(Arrays.copyOfRange(combined, 12, combined.length)), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("Cannot decrypt protected email", e); }
    }
}

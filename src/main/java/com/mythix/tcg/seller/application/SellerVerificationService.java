package com.mythix.tcg.seller.application;

import com.mythix.tcg.auth.application.InvalidCredentialsException;
import com.mythix.tcg.seller.api.SellerVerificationRequest;
import com.mythix.tcg.seller.api.SellerVerificationResponse;
import com.mythix.tcg.seller.domain.SellerProfile;
import com.mythix.tcg.seller.persistence.SellerProfileRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SellerVerificationService {
    private final UserRepository userRepository;
    private final SellerProfileRepository sellerProfileRepository;

    @Transactional
    public SellerVerificationResponse verify(Long userId, SellerVerificationRequest request) {
        User user = userRepository.findById(userId).orElseThrow(InvalidCredentialsException::new);
        if (sellerProfileRepository.existsByUserId(userId)) throw new SellerAlreadyVerifiedException();

        // Seller applications require admin approval before listing inventory.
        user.setRole("USER");
        user.setSellerStatus("PENDING");
        user.setPhone(trimToNull(request.phone()));
        user.setContactLine(trimToNull(request.line()));
        user.setContactFacebook(trimToNull(request.facebook()));
        LocalDateTime now = LocalDateTime.now();
        SellerProfile profile = sellerProfileRepository.save(SellerProfile.builder()
            .user(user).shopName(request.shopName().trim()).shopDescription(trimToNull(request.shopDescription()))
            .requestedAt(now).build());
        return SellerVerificationResponse.from(profile);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

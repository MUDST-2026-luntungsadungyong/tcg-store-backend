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

        user.setSellerStatus("PENDING");
        SellerProfile profile = sellerProfileRepository.save(SellerProfile.builder()
            .user(user).shopName(request.shopName().trim()).shopDescription(trimToNull(request.shopDescription()))
            .contactPhone(trimToNull(request.phone())).contactLine(trimToNull(request.line()))
            .contactFacebook(trimToNull(request.facebook())).requestedAt(LocalDateTime.now()).build());
        return SellerVerificationResponse.from(profile);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

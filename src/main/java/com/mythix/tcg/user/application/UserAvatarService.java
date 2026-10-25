package com.mythix.tcg.user.application;

import com.mythix.tcg.auth.application.InvalidCredentialsException;
import com.mythix.tcg.seller.application.SellerProductImageStore;
import com.mythix.tcg.user.api.ProfileResponse;
import com.mythix.tcg.user.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class UserAvatarService {
    private final UserRepository users;
    private final SellerProductImageStore images;

    @Transactional(readOnly = true)
    public SellerProductImageStore.ImageData read(Long userId) {
        var user = users.findById(userId).orElseThrow(InvalidCredentialsException::new);
        return images.read(user.getAvatarUrl());
    }

    @Transactional
    public ProfileResponse upload(Long userId, MultipartFile image) {
        var user = users.findById(userId).orElseThrow(InvalidCredentialsException::new);
        // Reuse validated, transaction-aware object storage. Old URLs may still
        // be referenced elsewhere, so replacing an avatar does not delete them.
        user.setAvatarUrl(images.store(image));
        return ProfileResponse.from(user);
    }
}

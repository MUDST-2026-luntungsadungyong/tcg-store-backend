package com.mythix.tcg.user.application;

import com.mythix.tcg.auth.application.InvalidCredentialsException;
import com.mythix.tcg.user.api.ProfileRequest;
import com.mythix.tcg.user.api.ProfileResponse;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserProfileService {
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public ProfileResponse getProfile(Long userId) {
        return ProfileResponse.from(findUser(userId));
    }

    @Transactional
    public ProfileResponse updateProfile(Long userId, ProfileRequest request) {
        User user = findUser(userId);
        user.setFullName(trimToNull(request.name()));
        user.setPhone(trimToNull(request.phone()));
        user.setContactLine(trimToNull(request.line()));
        user.setContactFacebook(trimToNull(request.facebook()));
        return ProfileResponse.from(user);
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId).orElseThrow(InvalidCredentialsException::new);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

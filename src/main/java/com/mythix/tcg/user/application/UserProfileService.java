package com.mythix.tcg.user.application;

import com.mythix.tcg.auth.application.InvalidCredentialsException;
import com.mythix.tcg.user.api.ProfileRequest;
import com.mythix.tcg.user.api.ProfileResponse;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Locale;

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
        if (request.name() != null) user.setFullName(trimToNull(request.name()));
        if (request.phone() != null) user.setPhone(trimToNull(request.phone()));
        if (request.line() != null) user.setContactLine(trimToNull(request.line()));
        if (request.facebook() != null) user.setContactFacebook(trimToNull(request.facebook()));
        if (request.avatarUrl() != null) user.setAvatarUrl(trimToNull(request.avatarUrl()));
        String email = trimToNull(request.email());
        if (email != null && !email.equalsIgnoreCase(user.getEmail())) {
            String normalized = email.toLowerCase(Locale.ROOT);
            if (userRepository.existsByEmail(normalized)) throw new com.mythix.tcg.auth.application.DuplicateEmailException();
            user.setEmail(normalized);
        }
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

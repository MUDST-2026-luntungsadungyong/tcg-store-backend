package com.mythix.tcg.user.api;

import com.mythix.tcg.auth.security.AuthenticatedUser;
import com.mythix.tcg.user.application.UserProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/profile")
@RequiredArgsConstructor
public class UserProfileController {
    private final UserProfileService userProfileService;

    @GetMapping
    public ProfileResponse getProfile(@AuthenticationPrincipal AuthenticatedUser user) {
        return userProfileService.getProfile(user.userId());
    }

    @PutMapping
    public ProfileResponse updateProfile(@AuthenticationPrincipal AuthenticatedUser user,
                                         @Valid @RequestBody ProfileRequest request) {
        return userProfileService.updateProfile(user.userId(), request);
    }
}

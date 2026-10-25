package com.mythix.tcg.user.api;

import com.mythix.tcg.auth.security.AuthenticatedUser;
import com.mythix.tcg.user.application.UserProfileService;
import com.mythix.tcg.user.application.UserAvatarService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
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
    private final UserAvatarService userAvatarService;

    @GetMapping("/avatar")
    public org.springframework.http.ResponseEntity<byte[]> getAvatar(@AuthenticationPrincipal AuthenticatedUser user) {
        var image = userAvatarService.read(user.userId());
        return org.springframework.http.ResponseEntity.ok()
            .contentType(org.springframework.http.MediaType.parseMediaType(image.contentType()))
            .cacheControl(org.springframework.http.CacheControl.noStore())
            .body(image.bytes());
    }

    @PostMapping(path = "/avatar", consumes = "multipart/form-data")
    public ProfileResponse uploadAvatar(@AuthenticationPrincipal AuthenticatedUser user,
        @RequestParam("image") MultipartFile image) {
        return userAvatarService.upload(user.userId(), image);
    }

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

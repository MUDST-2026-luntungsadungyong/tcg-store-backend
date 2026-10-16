package com.mythix.tcg.user.api;

import com.mythix.tcg.auth.security.AuthenticatedUser;
import com.mythix.tcg.user.application.UserProfileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("User: UserProfileController Unit Tests")
class UserProfileControllerTest {

    @Mock
    private UserProfileService userProfileService;

    @InjectMocks
    private UserProfileController controller;

    @Test
    @DisplayName("getProfile() - returns profile details for authenticated user")
    void getProfile_returnsAuthenticatedUserProfile() {
        AuthenticatedUser user = new AuthenticatedUser(1L, "ash@mythix.com", "USER");
        ProfileResponse mockResponse = new ProfileResponse(
                "1", "Ash Ketchum", "ash@mythix.com", "081-123-4567", "ash_line", "fb.com/ash", "https://img/avatar.png", "USER", "NONE"
        );

        when(userProfileService.getProfile(1L)).thenReturn(mockResponse);

        ProfileResponse response = controller.getProfile(user);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo("1");
        assertThat(response.name()).isEqualTo("Ash Ketchum");
        assertThat(response.email()).isEqualTo("ash@mythix.com");
        verify(userProfileService).getProfile(1L);
    }

    @Test
    @DisplayName("updateProfile() - updates profile details for authenticated user")
    void updateProfile_updatesDetailsSuccessfully() {
        AuthenticatedUser user = new AuthenticatedUser(1L, "ash@mythix.com", "USER");
        ProfileRequest request = new ProfileRequest("Ash Ketchum (Champion)", "081-999-9999", "champion_ash", "fb.com/champion", "ash@mythix.com", "https://img/champ.png");
        ProfileResponse updatedResponse = new ProfileResponse(
                "1", "Ash Ketchum (Champion)", "ash@mythix.com", "081-999-9999", "champion_ash", "fb.com/champion", "https://img/champ.png", "USER", "NONE"
        );

        when(userProfileService.updateProfile(1L, request)).thenReturn(updatedResponse);

        ProfileResponse response = controller.updateProfile(user, request);

        assertThat(response).isNotNull();
        assertThat(response.name()).isEqualTo("Ash Ketchum (Champion)");
        assertThat(response.phone()).isEqualTo("081-999-9999");
        verify(userProfileService).updateProfile(1L, request);
    }
}

package com.mythix.tcg.user.application;

import com.mythix.tcg.auth.application.DuplicateEmailException;
import com.mythix.tcg.auth.application.InvalidCredentialsException;
import com.mythix.tcg.user.api.ProfileRequest;
import com.mythix.tcg.user.api.ProfileResponse;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Sprint 1: UserProfileService Unit Tests (SCRUM-6 / SCRUM-16)")
class UserProfileServiceUnitTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserProfileService userProfileService;

    private User sampleUser;

    @BeforeEach
    void setUp() {
        sampleUser = User.builder()
            .id(1L)
            .email("ash@mythix.com")
            .fullName("Ash Ketchum")
            .phone("081-111-2222")
            .contactLine("ash_line")
            .contactFacebook("ash_fb")
            .role("USER")
            .sellerStatus("NONE")
            .build();
    }

    @Test
    @DisplayName("getProfile() - ส่งคืนโปรไฟล์สำเร็จเมื่อมี User ID อยู่จริง")
    void getProfile_success() {
        // Arrange
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));

        // Act
        ProfileResponse response = userProfileService.getProfile(1L);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo("1");
        assertThat(response.name()).isEqualTo("Ash Ketchum");
        assertThat(response.email()).isEqualTo("ash@mythix.com");
        assertThat(response.phone()).isEqualTo("081-111-2222");
    }

    @Test
    @DisplayName("getProfile() - โยน InvalidCredentialsException เมื่อไม่พบ User")
    void getProfile_userNotFound_throwsException() {
        // Arrange
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> userProfileService.getProfile(999L))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("updateProfile() - อัปเดตข้อมูลชื่อและเบอร์ติดต่อสำเร็จ")
    void updateProfile_success() {
        // Arrange
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        ProfileRequest request = new ProfileRequest("Red Ketchum", "089-999-0000", "red_line", "red_fb", null, "https://avatar.com/img.png");

        // Act
        ProfileResponse response = userProfileService.updateProfile(1L, request);

        // Assert
        assertThat(response).isNotNull();
        assertThat(sampleUser.getFullName()).isEqualTo("Red Ketchum");
        assertThat(sampleUser.getPhone()).isEqualTo("089-999-0000");
        assertThat(sampleUser.getContactLine()).isEqualTo("red_line");
        assertThat(sampleUser.getContactFacebook()).isEqualTo("red_fb");
        assertThat(sampleUser.getAvatarUrl()).isEqualTo("https://avatar.com/img.png");
    }

    @Test
    @DisplayName("updateProfile() - เปลี่ยนอีเมลใหม่สำเร็จเมื่ออีเมลไม่ซ้ำในระบบ")
    void updateProfile_changeEmail_success() {
        // Arrange
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(userRepository.existsByEmail("newemail@mythix.com")).thenReturn(false);
        ProfileRequest request = new ProfileRequest(null, null, null, null, "NewEmail@Mythix.com", null);

        // Act
        ProfileResponse response = userProfileService.updateProfile(1L, request);

        // Assert
        assertThat(response).isNotNull();
        assertThat(sampleUser.getEmail()).isEqualTo("newemail@mythix.com");
    }

    @Test
    @DisplayName("updateProfile() - โยน DuplicateEmailException เมื่อเปลี่ยนไปใช้อีเมลที่มีคนใช้อยู่แล้ว")
    void updateProfile_duplicateEmail_throwsException() {
        // Arrange
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(userRepository.existsByEmail("taken@mythix.com")).thenReturn(true);
        ProfileRequest request = new ProfileRequest(null, null, null, null, "taken@mythix.com", null);

        // Act & Assert
        assertThatThrownBy(() -> userProfileService.updateProfile(1L, request))
            .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    @DisplayName("updateProfile() - เมื่อส่งอีเมลเดิม (หรือตัวพิมพ์ต่างกัน) ต้องไม่อัปเดตซ้ำและไม่โยน Error")
    void updateProfile_sameEmail_doesNotThrow() {
        // Arrange
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        ProfileRequest request = new ProfileRequest("Updated Name", null, null, null, "ASH@MYTHIX.COM", null);

        // Act
        ProfileResponse response = userProfileService.updateProfile(1L, request);

        // Assert
        assertThat(response).isNotNull();
        verify(userRepository, never()).existsByEmail(anyString());
        assertThat(sampleUser.getEmail()).isEqualTo("ash@mythix.com");
    }
}

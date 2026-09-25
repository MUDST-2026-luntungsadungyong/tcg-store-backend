package com.mythix.tcg.seller.application;

import com.mythix.tcg.auth.application.InvalidCredentialsException;
import com.mythix.tcg.seller.api.SellerVerificationRequest;
import com.mythix.tcg.seller.api.SellerVerificationResponse;
import com.mythix.tcg.seller.domain.SellerProfile;
import com.mythix.tcg.seller.persistence.SellerProfileRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Sprint 1: SellerVerificationService Unit Tests (SCRUM-8 / SCRUM-16)")
class SellerVerificationServiceUnitTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private SellerProfileRepository sellerProfileRepository;

    @InjectMocks
    private SellerVerificationService sellerVerificationService;

    private User sampleUser;

    @BeforeEach
    void setUp() {
        sampleUser = User.builder()
            .id(2L)
            .email("brock@mythix.com")
            .fullName("Brock Pewter")
            .role("USER")
            .sellerStatus("NONE")
            .build();
    }

    @Test
    @DisplayName("verify() - ผู้ใช้ทั่วไปยื่นขอเป็นผู้ขายสำเร็จ ระบบอัปเดตสถานะ APPROVED และบันทึก Profile")
    void verify_success() {
        // Arrange
        SellerVerificationRequest request = new SellerVerificationRequest(
            "  Pewter City Cards  ",
            "  Authentic Pokémon Cards  ",
            "089-999-8888",
            "brock_line",
            "brock_fb"
        );
        when(userRepository.findById(2L)).thenReturn(Optional.of(sampleUser));
        when(sellerProfileRepository.existsByUserId(2L)).thenReturn(false);

        SellerProfile savedProfile = SellerProfile.builder()
            .id(1L)
            .user(sampleUser)
            .shopName("Pewter City Cards")
            .shopDescription("Authentic Pokémon Cards")
            .verifiedAt(LocalDateTime.now())
            .build();
        when(sellerProfileRepository.save(any(SellerProfile.class))).thenReturn(savedProfile);

        // Act
        SellerVerificationResponse response = sellerVerificationService.verify(2L, request);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.shopName()).isEqualTo("Pewter City Cards");
        assertThat(response.sellerStatus()).isEqualTo("APPROVED");

        assertThat(sampleUser.getRole()).isEqualTo("USER");
        assertThat(sampleUser.getSellerStatus()).isEqualTo("APPROVED");
        assertThat(sampleUser.getPhone()).isEqualTo("089-999-8888");
        assertThat(sampleUser.getContactLine()).isEqualTo("brock_line");
        assertThat(sampleUser.getContactFacebook()).isEqualTo("brock_fb");

        ArgumentCaptor<SellerProfile> profileCaptor = ArgumentCaptor.forClass(SellerProfile.class);
        verify(sellerProfileRepository).save(profileCaptor.capture());
        SellerProfile captured = profileCaptor.getValue();
        assertThat(captured.getShopName()).isEqualTo("Pewter City Cards");
        assertThat(captured.getShopDescription()).isEqualTo("Authentic Pokémon Cards");
    }

    @Test
    @DisplayName("verify() - ปฏิเสธและโยน SellerAlreadyApprovedException เมื่อผู้ใช้เป็นผู้ขายอยู่แล้ว")
    void verify_alreadyVerified_throwsException() {
        // Arrange
        when(userRepository.findById(2L)).thenReturn(Optional.of(sampleUser));
        when(sellerProfileRepository.existsByUserId(2L)).thenReturn(true);
        SellerVerificationRequest request = new SellerVerificationRequest("Shop", "Desc", null, null, null);

        // Act & Assert
        assertThatThrownBy(() -> sellerVerificationService.verify(2L, request))
            .isInstanceOf(SellerAlreadyApprovedException.class);

        verify(sellerProfileRepository, never()).save(any());
    }

    @Test
    @DisplayName("verify() - โยน InvalidCredentialsException เมื่อไม่พบ User")
    void verify_userNotFound_throwsException() {
        // Arrange
        when(userRepository.findById(999L)).thenReturn(Optional.empty());
        SellerVerificationRequest request = new SellerVerificationRequest("Shop", "Desc", null, null, null);

        // Act & Assert
        assertThatThrownBy(() -> sellerVerificationService.verify(999L, request))
            .isInstanceOf(InvalidCredentialsException.class);

        verify(sellerProfileRepository, never()).existsByUserId(any());
    }
}

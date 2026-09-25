package com.mythix.tcg.seller.application;

import com.mythix.tcg.auth.application.InvalidCredentialsException;
import com.mythix.tcg.seller.api.StoreProfileRequest;
import com.mythix.tcg.seller.api.StoreProfileResponse;
import com.mythix.tcg.seller.domain.SellerProfile;
import com.mythix.tcg.seller.persistence.SellerProfileRepository;
import com.mythix.tcg.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Sprint 1: StoreProfileService Unit Tests (SCRUM-16)")
class StoreProfileServiceUnitTest {

    @Mock
    private SellerProfileRepository repository;

    @InjectMocks
    private StoreProfileService storeProfileService;

    private SellerProfile sampleProfile;

    @BeforeEach
    void setUp() {
        User seller = User.builder()
            .id(2L)
            .email("brock@mythix.com")
            .fullName("Brock")
            .role("SELLER")
            .sellerStatus("APPROVED")
            .build();

        sampleProfile = SellerProfile.builder()
            .id(10L)
            .user(seller)
            .shopName("Pewter Gym Cards")
            .shopDescription("Rock solid card vault")
            .contactPhone("081-222-3333")
            .contactLine("pewter_line")
            .contactFacebook("pewter_fb")
            .verifiedAt(LocalDateTime.now())
            .build();
    }

    @Test
    @DisplayName("mine() - ดึงข้อมูลหน้าร้านของตนเองสำเร็จ")
    void mine_success() {
        // Arrange
        when(repository.findByUserId(2L)).thenReturn(Optional.of(sampleProfile));

        // Act
        StoreProfileResponse response = storeProfileService.mine(2L);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.shopName()).isEqualTo("Pewter Gym Cards");
        assertThat(response.shopDescription()).isEqualTo("Rock solid card vault");
        assertThat(response.phone()).isEqualTo("081-222-3333");
    }

    @Test
    @DisplayName("mine() - โยน InvalidCredentialsException เมื่อไม่พบข้อมูลร้านค้า")
    void mine_notFound_throwsException() {
        // Arrange
        when(repository.findByUserId(99L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> storeProfileService.mine(99L))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("update() - อัปเดตข้อมูลร้านค้าสำเร็จ")
    void update_success() {
        // Arrange
        when(repository.findByUserId(2L)).thenReturn(Optional.of(sampleProfile));
        StoreProfileRequest request = new StoreProfileRequest("  New Shop Name  ", "  New Description  ", "089-000-1111", null, null);

        // Act
        StoreProfileResponse response = storeProfileService.update(2L, request);

        // Assert
        assertThat(response).isNotNull();
        assertThat(sampleProfile.getShopName()).isEqualTo("New Shop Name");
        assertThat(sampleProfile.getShopDescription()).isEqualTo("New Description");
        assertThat(sampleProfile.getContactPhone()).isEqualTo("089-000-1111");
    }
}

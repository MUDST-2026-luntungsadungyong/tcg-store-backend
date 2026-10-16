package com.mythix.tcg.seller.api;

import com.mythix.tcg.auth.security.AuthenticatedUser;
import com.mythix.tcg.seller.application.SellerVerificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Seller: SellerVerificationController Unit Tests")
class SellerVerificationControllerTest {

    @Mock
    private SellerVerificationService sellerVerificationService;

    @InjectMocks
    private SellerVerificationController controller;

    @Test
    @DisplayName("verify() - submits seller verification application for authenticated user")
    void verify_submitsSellerApplication() {
        AuthenticatedUser user = new AuthenticatedUser(5L, "trainer@mythix.com", "USER");
        SellerVerificationRequest request = new SellerVerificationRequest(
                "Pallet Town Cards", "Beginner Trainer Cards", "081-555-6666", "pallet_line", "fb.com/pallet"
        );
        SellerVerificationResponse mockResponse = new SellerVerificationResponse(
                "10", "Pallet Town Cards", "Beginner Trainer Cards", "081-555-6666", "pallet_line", "fb.com/pallet", "PENDING", LocalDateTime.now()
        );

        when(sellerVerificationService.verify(5L, request)).thenReturn(mockResponse);

        SellerVerificationResponse response = controller.verify(user, request);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo("10");
        assertThat(response.shopName()).isEqualTo("Pallet Town Cards");
        assertThat(response.sellerStatus()).isEqualTo("PENDING");

        verify(sellerVerificationService).verify(5L, request);
    }
}

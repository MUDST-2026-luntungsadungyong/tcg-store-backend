package com.mythix.tcg.seller.api;

import com.mythix.tcg.auth.security.AuthenticatedUser;
import com.mythix.tcg.seller.application.StoreProfileService;
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
@DisplayName("Seller: StoreProfileController Unit Tests")
class StoreProfileControllerTest {

    @Mock
    private StoreProfileService service;

    @InjectMocks
    private StoreProfileController controller;

    @Test
    @DisplayName("mine() - returns seller store profile for authenticated user")
    void mine_returnsCurrentSellerStore() {
        AuthenticatedUser user = new AuthenticatedUser(2L, "seller@mythix.com", "USER");
        StoreProfileResponse mockResponse = new StoreProfileResponse("2", "Pewter Gym Cards", "Rock & Ground Cards", "081-111-2222", "pewter_line", "facebook.com/pewter");

        when(service.mine(2L)).thenReturn(mockResponse);

        StoreProfileResponse response = controller.mine(user);

        assertThat(response).isNotNull();
        assertThat(response.sellerId()).isEqualTo("2");
        assertThat(response.shopName()).isEqualTo("Pewter Gym Cards");
        verify(service).mine(2L);
    }

    @Test
    @DisplayName("update() - updates store details for authenticated user")
    void update_updatesStoreProfile() {
        AuthenticatedUser user = new AuthenticatedUser(2L, "seller@mythix.com", "USER");
        StoreProfileRequest request = new StoreProfileRequest("Updated Shop Name", "New Description", "081-999-8888", "new_line", "fb.com/new");
        StoreProfileResponse updatedResponse = new StoreProfileResponse("2", "Updated Shop Name", "New Description", "081-999-8888", "new_line", "fb.com/new");

        when(service.update(2L, request)).thenReturn(updatedResponse);

        StoreProfileResponse response = controller.update(user, request);

        assertThat(response).isNotNull();
        assertThat(response.shopName()).isEqualTo("Updated Shop Name");
        verify(service).update(2L, request);
    }

    @Test
    @DisplayName("publicStore() - returns public store profile by seller ID")
    void publicStore_returnsPublicProfile() {
        StoreProfileResponse mockResponse = new StoreProfileResponse("3", "Cerulean Water Cards", "Water Pokemon Cards", "081-333-4444", "misty_line", "facebook.com/misty");

        when(service.publicStore(3L)).thenReturn(mockResponse);

        StoreProfileResponse response = controller.publicStore(3L);

        assertThat(response).isNotNull();
        assertThat(response.sellerId()).isEqualTo("3");
        assertThat(response.shopName()).isEqualTo("Cerulean Water Cards");
        verify(service).publicStore(3L);
    }
}

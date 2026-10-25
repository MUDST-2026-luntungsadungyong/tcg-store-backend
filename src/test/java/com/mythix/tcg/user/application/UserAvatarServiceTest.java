package com.mythix.tcg.user.application;

import com.mythix.tcg.auth.application.InvalidCredentialsException;
import com.mythix.tcg.seller.application.SellerProductImageStore;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserAvatarServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final SellerProductImageStore images = mock(SellerProductImageStore.class);
    private final UserAvatarService service = new UserAvatarService(users, images);
    private final MockMultipartFile image = new MockMultipartFile("image", "photo.png", "image/png", new byte[]{1});

    @Test
    void readsOnlyTheAuthenticatedUsersSavedAvatar() {
        var user = User.builder().id(1L).avatarUrl("https://images.example/own.png").build();
        var data = new SellerProductImageStore.ImageData(new byte[]{1}, "image/png");
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(images.read(user.getAvatarUrl())).thenReturn(data);
        assertThat(service.read(1L)).isSameAs(data);
        verify(images).read("https://images.example/own.png");
    }

    @Test
    void storesAvatarForAuthenticatedUser() {
        var user = User.builder().id(1L).fullName("Collector").role("USER").build();
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(images.store(image)).thenReturn("https://images.example/products/photo.png");
        assertThat(service.upload(1L, image).avatarUrl()).isEqualTo("https://images.example/products/photo.png");
        assertThat(user.getAvatarUrl()).isEqualTo("https://images.example/products/photo.png");
    }

    @Test
    void rejectsUnknownUserBeforeWritingStorage() {
        when(users.findById(2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.upload(2L, image)).isInstanceOf(InvalidCredentialsException.class);
        verifyNoInteractions(images);
    }

    @Test
    void storageFailurePreservesPreviousAvatar() {
        var user = User.builder().id(1L).avatarUrl("previous.png").build();
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(images.store(image)).thenThrow(new IllegalStateException("storage unavailable"));
        assertThatThrownBy(() -> service.upload(1L, image)).isInstanceOf(IllegalStateException.class);
        assertThat(user.getAvatarUrl()).isEqualTo("previous.png");
    }
}

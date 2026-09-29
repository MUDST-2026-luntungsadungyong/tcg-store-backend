package com.mythix.tcg.admin.api;

import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUsersControllerTest {

    @Mock
    private UserRepository users;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AdminUsersController controller;

    @Test
    void list_returnsOnlyAdminAccountsMatchingTheSearch() {
        User matchingAdmin = User.builder().id(1L).fullName("Platform Admin").email("admin@mythix.com").role("ADMIN").sellerStatus("NONE").build();
        User otherAdmin = User.builder().id(2L).fullName("Operations Admin").email("ops@mythix.com").role("ADMIN").sellerStatus("NONE").build();
        when(users.findByRoleOrderByCreatedAtDesc("ADMIN")).thenReturn(List.of(matchingAdmin, otherAdmin));

        List<AdminUserResponse> result = controller.list("platform");

        assertThat(result).extracting(AdminUserResponse::email).containsExactly("admin@mythix.com");
        verify(users).findByRoleOrderByCreatedAtDesc("ADMIN");
    }

    @Test
    void create_savesANewAdminWithAnEncodedPassword() {
        when(users.existsByEmail("new.admin@mythix.com")).thenReturn(false);
        when(passwordEncoder.encode("AdminPass1")).thenReturn("encoded-password");
        when(users.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(3L);
            return user;
        });

        AdminUserResponse result = controller.create(new CreateAdminRequest("New Admin", " NEW.ADMIN@MYTHIX.COM ", "AdminPass1"));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(users).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getRole()).isEqualTo("ADMIN");
        assertThat(userCaptor.getValue().getSellerStatus()).isEqualTo("NONE");
        assertThat(userCaptor.getValue().getPassword()).isEqualTo("encoded-password");
        assertThat(result.email()).isEqualTo("new.admin@mythix.com");
    }

    @Test
    void create_rejectsAnExistingEmail() {
        when(users.existsByEmail("admin@mythix.com")).thenReturn(true);

        assertThatThrownBy(() -> controller.create(new CreateAdminRequest("Existing Admin", "admin@mythix.com", "AdminPass1")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Email already exists");
    }
}

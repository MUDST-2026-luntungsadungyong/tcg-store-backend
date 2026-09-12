package com.mythix.tcg.auth.application;

import com.mythix.tcg.auth.api.AuthResponse;
import com.mythix.tcg.auth.api.LoginRequest;
import com.mythix.tcg.auth.api.RegisterRequest;
import com.mythix.tcg.auth.api.UserResponse;
import com.mythix.tcg.auth.security.JwtService;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Sprint 1: AuthService Unit Tests (SCRUM-9)")
class AuthServiceUnitTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private AuthService authService;

    private User sampleUser;

    @BeforeEach
    void setUp() {
        sampleUser = User.builder()
            .id(1L)
            .email("ash@mythix.com")
            .fullName("Ash Ketchum")
            .password("hashed_password_123")
            .role("USER")
            .sellerStatus("NONE")
            .build();
    }

    @Test
    @DisplayName("register() - สมัครสมาชิกสำเร็จเมื่อใช้อีเมลใหม่และรหัสผ่านถูกเข้ารหัส")
    void register_success() {
        // Arrange
        RegisterRequest request = new RegisterRequest("  Ash Ketchum  ", "  Ash@Mythix.com  ", "password123");
        when(userRepository.existsByEmail("ash@mythix.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed_password_123");
        when(userRepository.save(any(User.class))).thenReturn(sampleUser);
        when(jwtService.generateToken(any(User.class))).thenReturn("mock_jwt_token");

        // Act
        AuthResponse response = authService.register(request);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.token()).isEqualTo("mock_jwt_token");
        assertThat(response.user().email()).isEqualTo("ash@mythix.com");
        assertThat(response.user().name()).isEqualTo("Ash Ketchum");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getEmail()).isEqualTo("ash@mythix.com");
        assertThat(savedUser.getFullName()).isEqualTo("Ash Ketchum");
        assertThat(savedUser.getPassword()).isEqualTo("hashed_password_123");
        assertThat(savedUser.getRole()).isEqualTo("USER");
        assertThat(savedUser.getSellerStatus()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("register() - ปฏิเสธและโยน DuplicateEmailException เมื่อใช้อีเมลซ้ำ")
    void register_duplicateEmail_throwsException() {
        // Arrange
        RegisterRequest request = new RegisterRequest("Ash", "ash@mythix.com", "password123");
        when(userRepository.existsByEmail("ash@mythix.com")).thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> authService.register(request))
            .isInstanceOf(DuplicateEmailException.class);

        verify(userRepository, never()).save(any());
        verify(jwtService, never()).generateToken(any());
    }

    @Test
    @DisplayName("login() - ล็อกอินสำเร็จเมื่อใส่อีเมลและรหัสผ่านถูกต้อง")
    void login_success() {
        // Arrange
        LoginRequest request = new LoginRequest("Ash@Mythix.com", "password123");
        when(userRepository.findByEmail("ash@mythix.com")).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches("password123", "hashed_password_123")).thenReturn(true);
        when(jwtService.generateToken(sampleUser)).thenReturn("valid_jwt_token");

        // Act
        AuthResponse response = authService.login(request);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.token()).isEqualTo("valid_jwt_token");
        assertThat(response.user().email()).isEqualTo("ash@mythix.com");
        verify(jwtService).generateToken(sampleUser);
    }

    @Test
    @DisplayName("login() - โยน InvalidCredentialsException เมื่อรหัสผ่านไม่ถูกต้อง")
    void login_wrongPassword_throwsException() {
        // Arrange
        LoginRequest request = new LoginRequest("ash@mythix.com", "wrong_password");
        when(userRepository.findByEmail("ash@mythix.com")).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches("wrong_password", "hashed_password_123")).thenReturn(false);

        // Act & Assert
        assertThatThrownBy(() -> authService.login(request))
            .isInstanceOf(InvalidCredentialsException.class);

        verify(jwtService, never()).generateToken(any());
    }

    @Test
    @DisplayName("login() - โยน InvalidCredentialsException เมื่อไม่พบอีเมลในระบบ")
    void login_emailNotFound_throwsException() {
        // Arrange
        LoginRequest request = new LoginRequest("unknown@mythix.com", "password123");
        when(userRepository.findByEmail("unknown@mythix.com")).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> authService.login(request))
            .isInstanceOf(InvalidCredentialsException.class);

        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(jwtService, never()).generateToken(any());
    }

    @Test
    @DisplayName("currentUser() - ส่งคืนข้อมูลโปรไฟล์เมื่อพบ User ID")
    void currentUser_success() {
        // Arrange
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));

        // Act
        UserResponse response = authService.currentUser(1L);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo("1");
        assertThat(response.email()).isEqualTo("ash@mythix.com");
        assertThat(response.name()).isEqualTo("Ash Ketchum");
    }

    @Test
    @DisplayName("currentUser() - โยน InvalidCredentialsException เมื่อไม่พบ User ID")
    void currentUser_notFound_throwsException() {
        // Arrange
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> authService.currentUser(999L))
            .isInstanceOf(InvalidCredentialsException.class);
    }
}

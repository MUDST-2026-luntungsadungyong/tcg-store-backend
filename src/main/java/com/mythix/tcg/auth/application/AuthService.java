package com.mythix.tcg.auth.application;

import com.mythix.tcg.auth.api.AuthResponse;
import com.mythix.tcg.auth.api.LoginRequest;
import com.mythix.tcg.auth.api.RegisterRequest;
import com.mythix.tcg.auth.api.UserResponse;
import com.mythix.tcg.auth.security.JwtService;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException();
        }

        User user = userRepository.save(User.builder()
            .email(email)
            .fullName(request.name().trim())
            .password(passwordEncoder.encode(request.password()))
            .role("USER")
            .sellerStatus("NONE")
            .build());
        return responseFor(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(normalizeEmail(request.email()))
            .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPassword()))
            .orElseThrow(InvalidCredentialsException::new);
        return responseFor(user);
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(InvalidCredentialsException::new);
        return UserResponse.from(user);
    }

    private AuthResponse responseFor(User user) {
        return new AuthResponse(jwtService.generateToken(user), UserResponse.from(user));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}

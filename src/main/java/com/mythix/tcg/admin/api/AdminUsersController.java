package com.mythix.tcg.admin.api;

import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUsersController {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    @GetMapping
    public List<AdminUserResponse> list(@RequestParam(defaultValue = "") String search) {
        String query = search.trim().toLowerCase(Locale.ROOT);
        return users.findByRoleOrderByCreatedAtDesc("ADMIN").stream()
            .filter(user -> query.isEmpty()
                || user.getEmail().toLowerCase(Locale.ROOT).contains(query)
                || user.getFullName().toLowerCase(Locale.ROOT).contains(query))
            .map(AdminUserResponse::from)
            .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public AdminUserResponse create(@Valid @RequestBody CreateAdminRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }

        User admin = users.save(User.builder()
            .email(email)
            .fullName(request.name().trim())
            .password(passwordEncoder.encode(request.password()))
            .role("ADMIN")
            .sellerStatus("NONE")
            .build());
        return AdminUserResponse.from(admin);
    }
}

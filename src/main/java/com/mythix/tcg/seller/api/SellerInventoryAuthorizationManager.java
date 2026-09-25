package com.mythix.tcg.seller.api;

import com.mythix.tcg.auth.security.AuthenticatedUser;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class SellerInventoryAuthorizationManager
    implements AuthorizationManager<RequestAuthorizationContext> {

    private final UserRepository userRepository;

    @Override
    public AuthorizationDecision check(
        Supplier<Authentication> authenticationSupplier,
        RequestAuthorizationContext context
    ) {
        Authentication authentication = authenticationSupplier.get();
        if (authentication == null
            || !authentication.isAuthenticated()
            || !(authentication.getPrincipal() instanceof AuthenticatedUser authenticatedUser)) {
            return new AuthorizationDecision(false);
        }

        boolean allowed = userRepository.findById(authenticatedUser.userId())
            .map(this::isSeller)
            .orElse(false);
        return new AuthorizationDecision(allowed);
    }

    private boolean isSeller(User user) {
        return "APPROVED".equalsIgnoreCase(user.getSellerStatus());
    }
}

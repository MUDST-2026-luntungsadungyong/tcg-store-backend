package com.mythix.tcg.seller.application;

import com.mythix.tcg.auth.application.InvalidCredentialsException;
import com.mythix.tcg.seller.api.StoreProfileRequest;
import com.mythix.tcg.seller.api.StoreProfileResponse;
import com.mythix.tcg.seller.domain.SellerProfile;
import com.mythix.tcg.seller.persistence.SellerProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class StoreProfileService {
    private final SellerProfileRepository repository;
    @Transactional(readOnly = true) public StoreProfileResponse mine(Long userId) { return StoreProfileResponse.from(find(userId)); }
    @Transactional(readOnly = true) public StoreProfileResponse publicStore(Long userId) { return StoreProfileResponse.from(find(userId)); }
    @Transactional public StoreProfileResponse update(Long userId, StoreProfileRequest request) {
        SellerProfile profile = find(userId);
        profile.setShopName(request.shopName().trim()); profile.setShopDescription(trim(request.shopDescription()));
        profile.getUser().setPhone(trim(request.phone()));
        profile.getUser().setContactLine(trim(request.line()));
        profile.getUser().setContactFacebook(trim(request.facebook()));
        return StoreProfileResponse.from(profile);
    }
    private SellerProfile find(Long userId) { return repository.findByUserId(userId).orElseThrow(InvalidCredentialsException::new); }
    private String trim(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
}

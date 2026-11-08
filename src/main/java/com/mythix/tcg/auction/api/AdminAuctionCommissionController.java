package com.mythix.tcg.auction.api;

import com.mythix.tcg.auction.application.AuctionCommissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping({"/api/admin/commission-policy", "/api/admin/auction-commission"})
@RequiredArgsConstructor
public class AdminAuctionCommissionController {
    private final AuctionCommissionService commission;

    @GetMapping
    public AuctionCommissionService.Policy get() {
        return commission.policy();
    }

    @PostMapping
    public AuctionCommissionService.Policy create(@Valid @RequestBody UpdatePolicy request) {
        return update(request);
    }

    @PutMapping
    public AuctionCommissionService.Policy update(@Valid @RequestBody UpdatePolicy request) {
        return commission.update(request.rate(), request.minimumFee());
    }

    public record UpdatePolicy(
        @NotNull @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 4) BigDecimal rate,
        @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal minimumFee
    ) {}
}

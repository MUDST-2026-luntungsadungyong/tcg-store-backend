package com.mythix.tcg.admin.api;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record RejectSellerApplicationRequest(@NotBlank @Size(max=2000) String reason) {}

package com.mythix.tcg.common.api;

import com.mythix.tcg.auth.application.DuplicateEmailException;
import com.mythix.tcg.auth.application.InvalidCredentialsException;
import com.mythix.tcg.seller.application.SellerAlreadyVerifiedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR", "Request validation failed", fields));
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ApiError> duplicateEmail(DuplicateEmailException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ApiError("EMAIL_ALREADY_EXISTS", exception.getMessage(), Map.of()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> invalidCredentials(InvalidCredentialsException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(new ApiError("INVALID_CREDENTIALS", exception.getMessage(), Map.of()));
    }

    @ExceptionHandler(SellerAlreadyVerifiedException.class)
    public ResponseEntity<ApiError> sellerAlreadyVerified(SellerAlreadyVerifiedException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ApiError("SELLER_ALREADY_VERIFIED", exception.getMessage(), Map.of()));
    }

    @ExceptionHandler(com.mythix.tcg.product.application.ProductNotFoundException.class)
    public ResponseEntity<ApiError> productNotFound(com.mythix.tcg.product.application.ProductNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ApiError("RESOURCE_NOT_FOUND", exception.getMessage(), Map.of()));
    }
}

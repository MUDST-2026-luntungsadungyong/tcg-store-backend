package com.mythix.tcg.common.api;

import com.mythix.tcg.auth.application.DuplicateEmailException;
import com.mythix.tcg.auth.application.InvalidCredentialsException;
import com.mythix.tcg.seller.application.InvalidSellerProductImageException;
import com.mythix.tcg.seller.application.SellerAlreadyVerifiedException;
import com.mythix.tcg.seller.application.SellerProductForbiddenException;
import com.mythix.tcg.seller.application.SellerProductHasActiveOrdersException;
import com.mythix.tcg.seller.application.SellerProductImageStorageException;
import com.mythix.tcg.seller.application.SellerProductMustBeHiddenException;
import com.mythix.tcg.seller.application.SellerProductNotFoundException;
import com.mythix.tcg.seller.application.SellerProductValidationException;
import com.mythix.tcg.seller.application.SellerRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        return validationResponse(exception.getBindingResult().getFieldErrors());
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiError> validation(BindException exception) {
        return validationResponse(exception.getBindingResult().getFieldErrors());
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

    @ExceptionHandler(SellerRequiredException.class)
    public ResponseEntity<ApiError> sellerRequired(SellerRequiredException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(new ApiError("SELLER_REQUIRED", exception.getMessage(), Map.of()));
    }

    @ExceptionHandler(SellerProductForbiddenException.class)
    public ResponseEntity<ApiError> productForbidden(SellerProductForbiddenException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(new ApiError("PRODUCT_FORBIDDEN", exception.getMessage(), Map.of()));
    }

    @ExceptionHandler(SellerProductNotFoundException.class)
    public ResponseEntity<ApiError> productNotFound(SellerProductNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ApiError("PRODUCT_NOT_FOUND", exception.getMessage(), Map.of()));
    }

    @ExceptionHandler(SellerProductMustBeHiddenException.class)
    public ResponseEntity<ApiError> productMustBeHidden(SellerProductMustBeHiddenException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ApiError("MUST_HIDE", exception.getMessage(), Map.of()));
    }

    @ExceptionHandler(SellerProductHasActiveOrdersException.class)
    public ResponseEntity<ApiError> productHasActiveOrders(SellerProductHasActiveOrdersException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ApiError("PRODUCT_HAS_ACTIVE_ORDERS", exception.getMessage(), Map.of()));
    }

    @ExceptionHandler(SellerProductValidationException.class)
    public ResponseEntity<ApiError> productValidation(SellerProductValidationException exception) {
        return ResponseEntity.badRequest().body(new ApiError(
            "VALIDATION_ERROR",
            "Request validation failed",
            Map.of(exception.getField(), exception.getMessage())
        ));
    }

    @ExceptionHandler(InvalidSellerProductImageException.class)
    public ResponseEntity<ApiError> invalidProductImage(InvalidSellerProductImageException exception) {
        return ResponseEntity.badRequest().body(new ApiError(
            exception.getCode(),
            exception.getMessage(),
            Map.of("image", exception.getMessage())
        ));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> imageTooLarge(MaxUploadSizeExceededException exception) {
        String message = "Product image must not exceed 5 MiB";
        return ResponseEntity.badRequest()
            .body(new ApiError("IMAGE_TOO_LARGE", message, Map.of("image", message)));
    }

    @ExceptionHandler(SellerProductImageStorageException.class)
    public ResponseEntity<ApiError> imageStorage(SellerProductImageStorageException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ApiError("IMAGE_STORAGE_ERROR", "The product image could not be saved", Map.of()));
    }

    private ResponseEntity<ApiError> validationResponse(Iterable<FieldError> fieldErrors) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : fieldErrors) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
            .body(new ApiError("VALIDATION_ERROR", "Request validation failed", fields));
    }
}

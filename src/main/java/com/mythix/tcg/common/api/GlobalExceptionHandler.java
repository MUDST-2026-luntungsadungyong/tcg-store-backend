package com.mythix.tcg.common.api;

import com.mythix.tcg.auth.application.DuplicateEmailException;
import com.mythix.tcg.auth.application.InvalidCredentialsException;
import com.mythix.tcg.cart.application.CartItemNotFoundException;
import com.mythix.tcg.cart.application.CartListingNotAvailableException;
import com.mythix.tcg.cart.application.CartListingNotFoundException;
import com.mythix.tcg.cart.application.CartNotAllowedException;
import com.mythix.tcg.cart.application.CartValidationException;
import com.mythix.tcg.cart.application.InsufficientStockException;
import com.mythix.tcg.seller.application.InvalidSellerProductImageException;
import com.mythix.tcg.seller.application.SellerAlreadyVerifiedException;
import com.mythix.tcg.seller.application.SellerProductForbiddenException;
import com.mythix.tcg.seller.application.SellerProductHasActiveOrdersException;
import com.mythix.tcg.seller.application.SellerProductImageStorageException;
import com.mythix.tcg.seller.application.SellerProductMustBeHiddenException;
import com.mythix.tcg.seller.application.SellerProductNotFoundException;
import com.mythix.tcg.collection.application.CardNotFoundException;
import com.mythix.tcg.collection.application.CollectionListingNotFoundException;
import com.mythix.tcg.collection.application.CollectionItemNotFoundException;
import com.mythix.tcg.collection.application.CollectionValidationException;
import com.mythix.tcg.collection.application.CollectionUserNotFoundException;
import jakarta.validation.ConstraintViolationException;
import com.mythix.tcg.order.application.OrderNotFoundException;
import com.mythix.tcg.seller.application.SellerProductValidationException;
import com.mythix.tcg.seller.application.SellerRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

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

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> methodValidation(HandlerMethodValidationException exception) {
        return ResponseEntity.badRequest().body(new ApiError(
            "VALIDATION_ERROR",
            "Request validation failed",
            Map.of("request", "Request parameters are invalid")
        ));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> constraintViolation(ConstraintViolationException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getConstraintViolations().forEach(violation -> {
            String path = violation.getPropertyPath().toString();
            fields.putIfAbsent(path.substring(path.lastIndexOf('.') + 1), violation.getMessage());
        });
        return ResponseEntity.badRequest()
            .body(new ApiError("VALIDATION_ERROR", "Request validation failed", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadableRequest(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(new ApiError(
            "VALIDATION_ERROR",
            "Request body is invalid",
            Map.of("request", "Request body is invalid")
        ));
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

    @ExceptionHandler(CartNotAllowedException.class)
    public ResponseEntity<ApiError> cartNotAllowed(CartNotAllowedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(new ApiError("CART_NOT_ALLOWED", exception.getMessage(), Map.of()));
    }

    @ExceptionHandler(CartValidationException.class)
    public ResponseEntity<ApiError> cartValidation(CartValidationException exception) {
        return ResponseEntity.badRequest().body(new ApiError(
            "VALIDATION_ERROR",
            "Request validation failed",
            Map.of(exception.getField(), exception.getMessage())
        ));
    }

    @ExceptionHandler(CartListingNotFoundException.class)
    public ResponseEntity<ApiError> cartListingNotFound(CartListingNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ApiError("LISTING_NOT_FOUND", exception.getMessage(), Map.of()));
    }

    @ExceptionHandler(CartItemNotFoundException.class)
    public ResponseEntity<ApiError> cartItemNotFound(CartItemNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ApiError("CART_ITEM_NOT_FOUND", exception.getMessage(), Map.of()));
    }

    @ExceptionHandler(CartListingNotAvailableException.class)
    public ResponseEntity<ApiError> cartListingNotAvailable(CartListingNotAvailableException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ApiError("LISTING_NOT_AVAILABLE", exception.getMessage(), Map.of()));
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ApiError> insufficientStock(InsufficientStockException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ApiError("INSUFFICIENT_STOCK", exception.getMessage(), Map.of()));
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

    @ExceptionHandler(CollectionItemNotFoundException.class)
    public ResponseEntity<ApiError> collectionItemNotFound(CollectionItemNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ApiError("COLLECTION_ITEM_NOT_FOUND", exception.getMessage(), Map.of()));
    }

    @ExceptionHandler(CardNotFoundException.class)
    public ResponseEntity<ApiError> cardNotFound(CardNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ApiError("CARD_NOT_FOUND", exception.getMessage(), Map.of()));
    }

    @ExceptionHandler(CollectionListingNotFoundException.class)
    public ResponseEntity<ApiError> collectionListingNotFound(CollectionListingNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ApiError("LISTING_NOT_FOUND", exception.getMessage(), Map.of()));
    }

    @ExceptionHandler(CollectionValidationException.class)
    public ResponseEntity<ApiError> collectionValidation(CollectionValidationException exception) {
        return ResponseEntity.badRequest().body(new ApiError(
            "VALIDATION_ERROR",
            "Request validation failed",
            Map.of(exception.getField(), exception.getMessage())
        ));
    }

    @ExceptionHandler(CollectionUserNotFoundException.class)
    public ResponseEntity<ApiError> collectionUserNotFound(CollectionUserNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(new ApiError("INVALID_CREDENTIALS", "Authenticated user is no longer available", Map.of()));
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ApiError> orderNotFound(OrderNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ApiError("ORDER_NOT_FOUND", exception.getMessage(), Map.of()));
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

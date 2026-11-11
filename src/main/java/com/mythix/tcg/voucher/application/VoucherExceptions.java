package com.mythix.tcg.voucher.application;

import java.util.Collections;
import java.util.Map;

public class VoucherExceptions {

    public static class VoucherNotFoundException extends RuntimeException {
        public VoucherNotFoundException(String message) {
            super(message);
        }
    }

    public static class VoucherAlreadyClaimedException extends RuntimeException {
        public VoucherAlreadyClaimedException(String message) {
            super(message);
        }
    }

    public static class VoucherQuotaExhaustedException extends RuntimeException {
        public VoucherQuotaExhaustedException(String message) {
            super(message);
        }
    }

    public static class VoucherNotApplicableException extends RuntimeException {
        public VoucherNotApplicableException(String message) {
            super(message);
        }
    }

    public static class VoucherCodeAlreadyExistsException extends RuntimeException {
        public VoucherCodeAlreadyExistsException(String message) {
            super(message);
        }
    }

    public static class VoucherValidationException extends RuntimeException {
        private final Map<String, String> fieldErrors;

        public VoucherValidationException(String message, Map<String, String> fieldErrors) {
            super(message);
            this.fieldErrors = fieldErrors == null ? Collections.emptyMap() : Map.copyOf(fieldErrors);
        }

        public VoucherValidationException(String field, String message) {
            this(message, Map.of(field, message));
        }

        public Map<String, String> getFieldErrors() {
            return fieldErrors;
        }
    }
}

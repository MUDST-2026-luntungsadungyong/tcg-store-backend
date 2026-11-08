package com.mythix.tcg.voucher.application;

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
}

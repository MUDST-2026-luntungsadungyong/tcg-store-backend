package com.mythix.tcg.seller.application;

/**
 * Compatibility type for callers compiled against the pre-Revision 5 class name.
 * Seller state is now represented exclusively by seller_status=APPROVED.
 */
@Deprecated(forRemoval = false)
public class SellerAlreadyVerifiedException extends SellerAlreadyApprovedException {
    public SellerAlreadyVerifiedException() {
        super();
    }
}

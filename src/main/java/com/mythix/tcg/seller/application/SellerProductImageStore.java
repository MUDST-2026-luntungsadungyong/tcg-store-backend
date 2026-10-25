package com.mythix.tcg.seller.application;

import org.springframework.web.multipart.MultipartFile;

/**
 * Shared contract for seller product image storage implementations.
 */
public interface SellerProductImageStore {

    String store(MultipartFile image);

    void delete(String imageUrl);

    record ImageData(byte[] bytes, String contentType) {}

    ImageData read(String imageUrl);
}

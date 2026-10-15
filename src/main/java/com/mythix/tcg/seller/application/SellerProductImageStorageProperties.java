package com.mythix.tcg.seller.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration for local and S3-compatible image storage.
 */
@ConfigurationProperties(prefix = "app.seller.product-images")
public record SellerProductImageStorageProperties(
    StorageType storage,
    String storageDirectory,
    String publicBaseUrl,
    String objectStorageEndpoint,
    String objectStorageRegion,
    String objectStorageBucket,
    String objectStoragePublicBaseUrl,
    boolean objectStoragePathStyle,
    String objectStorageAccessKey,
    String objectStorageSecretKey
) {

    public SellerProductImageStorageProperties {
        storage = storage == null ? StorageType.LOCAL : storage;
        storageDirectory = defaultValue(storageDirectory, "uploads/products");
        publicBaseUrl = defaultValue(publicBaseUrl, "http://localhost:8080");
        objectStorageRegion = defaultValue(objectStorageRegion, "us-east-1");
        objectStoragePublicBaseUrl = defaultValue(objectStoragePublicBaseUrl, "");
    }

    public void validate() {
        if (storage == StorageType.LOCAL) {
            if (storageDirectory.isBlank() || publicBaseUrl.isBlank()) {
                throw new IllegalStateException("Local product image storage requires directory and public base URL");
            }
            return;
        }

        requireNonBlank(objectStorageRegion, "OBJECT_STORAGE_REGION");
        requireNonBlank(objectStorageBucket, "OBJECT_STORAGE_BUCKET");
        requireNonBlank(objectStoragePublicBaseUrl, "OBJECT_STORAGE_PUBLIC_BASE_URL");
        requireNonBlank(objectStorageAccessKey, "OBJECT_STORAGE_ACCESS_KEY");
        requireNonBlank(objectStorageSecretKey, "OBJECT_STORAGE_SECRET_KEY");
        if (objectStorageEndpoint != null && !objectStorageEndpoint.isBlank()) {
            try {
                java.net.URI endpoint = java.net.URI.create(objectStorageEndpoint);
                if (endpoint.getScheme() == null || endpoint.getHost() == null) {
                    throw new IllegalArgumentException("URI must be absolute and include a host");
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                    "OBJECT_STORAGE_ENDPOINT must be a valid absolute URI with a host",
                    exception
                );
            }
        }
    }

    private static String defaultValue(String value, String fallback) {
        return value == null ? fallback : value.trim();
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when S3 image storage is active");
        }
    }

    public enum StorageType {
        LOCAL,
        S3
    }
}

package com.mythix.tcg.seller.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * S3-compatible implementation for shared product image storage.
 */
public class S3SellerProductImageStorage implements SellerProductImageStore {

    private static final Logger log = LoggerFactory.getLogger(S3SellerProductImageStorage.class);
    private static final String OBJECT_PREFIX = "products/";
    private static final Pattern MANAGED_KEY = Pattern.compile(
        "products/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:png|jpg|webp)"
    );

    private final S3Client client;
    private final String bucket;
    private final String publicUrlPrefix;
    private final SellerProductImageValidator validator;

    public S3SellerProductImageStorage(
        S3Client client,
        SellerProductImageStorageProperties properties
    ) {
        this.client = client;
        this.bucket = properties.objectStorageBucket();
        this.publicUrlPrefix = stripTrailingSlashes(properties.objectStoragePublicBaseUrl()) + "/";
        this.validator = new SellerProductImageValidator();
    }

    @Override
    public String store(MultipartFile image) {
        SellerProductImageValidator.ValidatedImage validated = validator.validate(image);
        String key = OBJECT_PREFIX + UUID.randomUUID() + validated.extension();
        try {
            client.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(validated.contentType())
                    .cacheControl("public, max-age=31536000, immutable")
                    .build(),
                RequestBody.fromBytes(validated.content())
            );
            removeOnRollback(key);
            return publicUrlPrefix + key;
        } catch (RuntimeException exception) {
            throw new SellerProductImageStorageException("Could not store the product image", exception);
        }
    }

    @Override
    public void delete(String imageUrl) {
        String key = resolveManagedKey(imageUrl);
        if (key == null) {
            return;
        }

        if (transactionSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteAfterCommit(key);
                }
            });
            return;
        }

        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (RuntimeException exception) {
            throw new SellerProductImageStorageException("Could not remove the product image", exception);
        }
    }

    private String resolveManagedKey(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank() || !imageUrl.startsWith(publicUrlPrefix)) {
            return null;
        }
        String key = imageUrl.substring(publicUrlPrefix.length());
        return MANAGED_KEY.matcher(key).matches() ? key : null;
    }

    private void removeOnRollback(String key) {
        if (!transactionSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    deleteQuietly(key);
                }
            }
        });
    }

    private boolean transactionSynchronizationActive() {
        return TransactionSynchronizationManager.isActualTransactionActive()
            && TransactionSynchronizationManager.isSynchronizationActive();
    }

    private void deleteAfterCommit(String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (RuntimeException exception) {
            log.warn("Could not remove committed product image object {}/{}", bucket, key, exception);
        }
    }

    private void deleteQuietly(String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (RuntimeException exception) {
            log.warn("Could not clean up product image object {}/{}", bucket, key, exception);
        }
    }

    private static String stripTrailingSlashes(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Object storage public base URL must not be blank");
        }
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            URI uri = URI.create(normalized);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("Object storage public base URL must be absolute");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Object storage public base URL must be a valid absolute URL", exception);
        }
        return normalized;
    }
}

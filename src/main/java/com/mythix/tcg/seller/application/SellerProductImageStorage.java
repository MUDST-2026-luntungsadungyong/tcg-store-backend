package com.mythix.tcg.seller.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Filesystem implementation retained for local development and tests.
 */
public class SellerProductImageStorage implements SellerProductImageStore {

    public static final long MAX_IMAGE_SIZE_BYTES = SellerProductImageValidator.MAX_IMAGE_SIZE_BYTES;
    public static final String RESOURCE_HANDLER_PATTERN = "/uploads/products/**";
    public static final String PUBLIC_PATH_PREFIX = "/uploads/products/";

    private static final Logger log = LoggerFactory.getLogger(SellerProductImageStorage.class);
    private static final Pattern MANAGED_FILENAME = Pattern.compile(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:png|jpg|webp)"
    );

    private final Path storageRoot;
    private final String publicUrlPrefix;
    private final SellerProductImageValidator validator;

    public SellerProductImageStorage(String storageDirectory, String publicBaseUrl) {
        if (storageDirectory == null || storageDirectory.isBlank()) {
            throw new IllegalArgumentException("Product image storage directory must not be blank");
        }
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new IllegalArgumentException("Product image public base URL must not be blank");
        }

        this.storageRoot = Path.of(storageDirectory).toAbsolutePath().normalize();
        this.publicUrlPrefix = stripTrailingSlashes(publicBaseUrl.trim()) + PUBLIC_PATH_PREFIX;
        this.validator = new SellerProductImageValidator();
    }

    @Override
    public String store(MultipartFile image) {
        SellerProductImageValidator.ValidatedImage validated = validator.validate(image);
        Path temporaryFile = null;
        try {
            ensureStorageDirectory();
            temporaryFile = safeResolve("." + UUID.randomUUID() + ".upload");
            Files.write(
                temporaryFile,
                validated.content(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            );
            Path target = safeResolve(UUID.randomUUID() + validated.extension());
            moveIntoPlace(temporaryFile, target);
            temporaryFile = null;

            removeOnRollback(target);
            return publicUrlPrefix + target.getFileName();
        } catch (IOException exception) {
            throw new SellerProductImageStorageException("Could not store the product image", exception);
        } finally {
            deleteQuietly(temporaryFile);
        }
    }

    @Override
    public void delete(String imageUrl) {
        Path managedFile = resolveManagedFile(imageUrl);
        if (managedFile == null) {
            return;
        }

        if (transactionSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteAfterCommit(managedFile);
                }
            });
            return;
        }

        try {
            Files.deleteIfExists(managedFile);
        } catch (IOException exception) {
            throw new SellerProductImageStorageException("Could not remove the product image", exception);
        }
    }

    public URI resourceLocation() {
        return storageRoot.toUri();
    }

    private void ensureStorageDirectory() throws IOException {
        Files.createDirectories(storageRoot);
        if (!Files.isDirectory(storageRoot)) {
            throw new IOException("Configured product image path is not a directory");
        }
    }

    private Path safeResolve(String filename) {
        Path resolved = storageRoot.resolve(filename).normalize();
        if (!resolved.startsWith(storageRoot) || !storageRoot.equals(resolved.getParent())) {
            throw new SellerProductImageStorageException("Unsafe product image storage path");
        }
        return resolved;
    }

    private Path resolveManagedFile(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }

        String filename;
        if (imageUrl.startsWith(publicUrlPrefix)) {
            filename = imageUrl.substring(publicUrlPrefix.length());
        } else {
            try {
                URI uri = URI.create(imageUrl);
                if (uri.isAbsolute() || uri.getRawAuthority() != null) {
                    return null;
                }
                String path = uri.getPath();
                if (path == null || !path.startsWith(PUBLIC_PATH_PREFIX)) {
                    return null;
                }
                filename = path.substring(PUBLIC_PATH_PREFIX.length());
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }

        if (!MANAGED_FILENAME.matcher(filename).matches()) {
            return null;
        }
        return safeResolve(filename);
    }

    private void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void removeOnRollback(Path createdFile) {
        if (!transactionSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    deleteQuietly(createdFile);
                }
            }
        });
    }

    private boolean transactionSynchronizationActive() {
        return TransactionSynchronizationManager.isActualTransactionActive()
            && TransactionSynchronizationManager.isSynchronizationActive();
    }

    private void deleteAfterCommit(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            log.warn("Could not remove committed product image file {}", file, exception);
        }
    }

    private void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            log.warn("Could not clean up product image file {}", file, exception);
        }
    }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        if (end == 0) {
            throw new IllegalArgumentException("Product image public base URL must not be blank");
        }
        return value.substring(0, end);
    }
}

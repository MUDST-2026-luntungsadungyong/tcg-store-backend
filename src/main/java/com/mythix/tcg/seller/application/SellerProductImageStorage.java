package com.mythix.tcg.seller.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stores seller-uploaded product images on the local filesystem.
 *
 * <p>Original filenames are never used. File type is determined from magic
 * bytes and must agree with the declared content type. Files created in a
 * transaction are removed if that transaction rolls back; deletion of an old
 * image is delayed until commit.</p>
 */
@Service
public class SellerProductImageStorage {

    public static final long MAX_IMAGE_SIZE_BYTES = 5L * 1024L * 1024L;
    public static final String RESOURCE_HANDLER_PATTERN = "/uploads/products/**";
    public static final String PUBLIC_PATH_PREFIX = "/uploads/products/";

    private static final Logger log = LoggerFactory.getLogger(SellerProductImageStorage.class);
    private static final int SIGNATURE_LENGTH = 12;
    private static final Pattern MANAGED_FILENAME = Pattern.compile(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:png|jpg|webp)"
    );

    private final Path storageRoot;
    private final String publicUrlPrefix;

    public SellerProductImageStorage(
        @Value("${app.seller.product-images.storage-directory:uploads/products}") String storageDirectory,
        @Value("${app.seller.product-images.public-base-url:http://localhost:8080}") String publicBaseUrl
    ) {
        if (storageDirectory == null || storageDirectory.isBlank()) {
            throw new IllegalArgumentException("Product image storage directory must not be blank");
        }
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new IllegalArgumentException("Product image public base URL must not be blank");
        }

        this.storageRoot = Path.of(storageDirectory).toAbsolutePath().normalize();
        this.publicUrlPrefix = stripTrailingSlashes(publicBaseUrl.trim()) + PUBLIC_PATH_PREFIX;
    }

    /**
     * Saves a PNG, JPEG, or WebP image and returns its public URL.
     */
    public String store(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw invalid("IMAGE_REQUIRED", "Product image is required");
        }
        if (image.getSize() > MAX_IMAGE_SIZE_BYTES) {
            throw invalid("IMAGE_TOO_LARGE", "Product image must not exceed 5 MiB");
        }

        Path temporaryFile = null;
        try {
            ensureStorageDirectory();
            temporaryFile = safeResolve("." + UUID.randomUUID() + ".upload");
            byte[] signature = copyAndReadSignature(image, temporaryFile);
            ImageFormat format = detectFormat(signature);
            validateDeclaredContentType(image.getContentType(), format);

            String filename = UUID.randomUUID() + format.extension;
            Path target = safeResolve(filename);
            moveIntoPlace(temporaryFile, target);
            temporaryFile = null;

            removeOnRollback(target);
            return publicUrlPrefix + filename;
        } catch (InvalidSellerProductImageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new SellerProductImageStorageException("Could not store the product image", exception);
        } finally {
            deleteQuietly(temporaryFile);
        }
    }

    /**
     * Deletes an image only when the URL points to a UUID-named file managed by
     * this storage service. External and malformed URLs are ignored.
     *
     * <p>When called inside a transaction, deletion occurs only after a
     * successful commit.</p>
     */
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

    /**
     * Filesystem location used by the MVC resource handler.
     */
    public URI resourceLocation() {
        return storageRoot.toUri();
    }

    private byte[] copyAndReadSignature(MultipartFile image, Path temporaryFile) throws IOException {
        byte[] signature = new byte[SIGNATURE_LENGTH];
        int signatureBytes = 0;
        long totalBytes = 0;

        try (InputStream input = image.getInputStream();
             OutputStream output = Files.newOutputStream(
                 temporaryFile,
                 StandardOpenOption.CREATE_NEW,
                 StandardOpenOption.WRITE
             )) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                totalBytes += bytesRead;
                if (totalBytes > MAX_IMAGE_SIZE_BYTES) {
                    throw invalid("IMAGE_TOO_LARGE", "Product image must not exceed 5 MiB");
                }

                if (signatureBytes < SIGNATURE_LENGTH) {
                    int bytesToCopy = Math.min(bytesRead, SIGNATURE_LENGTH - signatureBytes);
                    System.arraycopy(buffer, 0, signature, signatureBytes, bytesToCopy);
                    signatureBytes += bytesToCopy;
                }
                output.write(buffer, 0, bytesRead);
            }
        }

        if (totalBytes == 0) {
            throw invalid("IMAGE_REQUIRED", "Product image is required");
        }
        return Arrays.copyOf(signature, signatureBytes);
    }

    private ImageFormat detectFormat(byte[] signature) {
        if (startsWith(signature, new int[] {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) {
            return ImageFormat.PNG;
        }
        if (startsWith(signature, new int[] {0xff, 0xd8, 0xff})) {
            return ImageFormat.JPEG;
        }
        if (signature.length >= 12
            && asciiEquals(signature, 0, "RIFF")
            && asciiEquals(signature, 8, "WEBP")) {
            return ImageFormat.WEBP;
        }
        throw invalid("IMAGE_TYPE_UNSUPPORTED", "Product image must be a PNG, JPEG, or WebP file");
    }

    private void validateDeclaredContentType(String contentType, ImageFormat format) {
        if (contentType == null || contentType.isBlank()) {
            throw invalid("IMAGE_TYPE_UNSUPPORTED", "Product image content type is required");
        }
        String normalized = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!format.contentTypes.contains(normalized)) {
            throw invalid(
                "IMAGE_TYPE_MISMATCH",
                "Product image content type does not match its file contents"
            );
        }
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

    private static boolean startsWith(byte[] bytes, int[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (Byte.toUnsignedInt(bytes[index]) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean asciiEquals(byte[] bytes, int offset, String expected) {
        for (int index = 0; index < expected.length(); index++) {
            if (bytes[offset + index] != (byte) expected.charAt(index)) {
                return false;
            }
        }
        return true;
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

    private static InvalidSellerProductImageException invalid(String code, String message) {
        return new InvalidSellerProductImageException(code, message);
    }

    private enum ImageFormat {
        PNG(".png", Set.of("image/png")),
        JPEG(".jpg", Set.of("image/jpeg", "image/jpg", "image/pjpeg")),
        WEBP(".webp", Set.of("image/webp"));

        private final String extension;
        private final Set<String> contentTypes;

        ImageFormat(String extension, Set<String> contentTypes) {
            this.extension = extension;
            this.contentTypes = contentTypes;
        }
    }
}

package com.mythix.tcg.seller.application;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Validates image content independently of the storage backend.
 */
public final class SellerProductImageValidator {

    public static final long MAX_IMAGE_SIZE_BYTES = 5L * 1024L * 1024L;
    private static final int SIGNATURE_LENGTH = 12;

    public ValidatedImage validate(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw invalid("IMAGE_REQUIRED", "Product image is required");
        }
        if (image.getSize() > MAX_IMAGE_SIZE_BYTES) {
            throw invalid("IMAGE_TOO_LARGE", "Product image must not exceed 5 MiB");
        }

        try (InputStream input = image.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                (int) Math.min(Math.max(image.getSize(), 0), MAX_IMAGE_SIZE_BYTES)
            );
            byte[] signature = new byte[SIGNATURE_LENGTH];
            int signatureBytes = 0;
            byte[] buffer = new byte[8192];
            long totalBytes = 0;
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

            if (totalBytes == 0) {
                throw invalid("IMAGE_REQUIRED", "Product image is required");
            }

            ImageFormat format = detectFormat(Arrays.copyOf(signature, signatureBytes));
            String contentType = normalizeContentType(image.getContentType());
            if (!format.contentTypes().contains(contentType)) {
                throw invalid(
                    "IMAGE_TYPE_MISMATCH",
                    "Product image content type does not match its file contents"
                );
            }
            return new ValidatedImage(output.toByteArray(), format.extension(), contentType);
        } catch (InvalidSellerProductImageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new SellerProductImageStorageException("Could not read the product image", exception);
        }
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

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw invalid("IMAGE_TYPE_UNSUPPORTED", "Product image content type is required");
        }
        return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
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
        byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < offset + expectedBytes.length) {
            return false;
        }
        for (int index = 0; index < expectedBytes.length; index++) {
            if (bytes[offset + index] != expectedBytes[index]) {
                return false;
            }
        }
        return true;
    }

    private static InvalidSellerProductImageException invalid(String code, String message) {
        return new InvalidSellerProductImageException(code, message);
    }

    public record ValidatedImage(byte[] content, String extension, String contentType) {
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

        String extension() {
            return extension;
        }

        Set<String> contentTypes() {
            return contentTypes;
        }
    }
}

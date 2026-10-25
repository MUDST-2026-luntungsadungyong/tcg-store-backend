package com.mythix.tcg.seller.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.core.sync.RequestBody;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class S3SellerProductImageStorageTests {

    private final S3Client client = mock(S3Client.class);

    @Test
    void readsManagedImageBytesFromConfiguredBucket() {
        var requestType = software.amazon.awssdk.services.s3.model.GetObjectRequest.class;
        org.mockito.Mockito.when(client.getObjectAsBytes(any(requestType))).thenReturn(
            software.amazon.awssdk.core.ResponseBytes.fromByteArray(
                software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().build(), new byte[]{1, 2, 3}));
        var image = storage().read("https://images.example.test/products/12345678-1234-1234-1234-123456789abc.png");
        assertThat(image.bytes()).containsExactly(1, 2, 3);
        assertThat(image.contentType()).isEqualTo("image/png");
        verify(client).getObjectAsBytes(argThat((software.amazon.awssdk.services.s3.model.GetObjectRequest request) ->
            request.bucket().equals("tcg-product-images") && request.key().equals("products/12345678-1234-1234-1234-123456789abc.png")));
    }

    @Test
    void rejectsUnmanagedUrlsBeforeAccessingStorage() {
        assertThatThrownBy(() -> storage().read("http://localhost:8080/private"))
            .isInstanceOf(SellerProductImageStorageException.class);
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void storesValidatedImageWithStablePublicUrlAndMetadata() {
        S3SellerProductImageStorage storage = storage();

        String url = storage.store(new MockMultipartFile(
            "image",
            "../../unsafe.png",
            "image/png",
            bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        ));

        assertThat(url).as("public URL contains only a generated managed key").matches(
            "https://images\\.example\\.test/products/"
                + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png"
        );
        verify(client).putObject(
            argThat((PutObjectRequest request) ->
                request.bucket().equals("tcg-product-images")
                    && request.key().matches("products/[0-9a-f-]{36}\\.png")
                    && request.contentType().equals("image/png")
                    && request.cacheControl().contains("immutable")
            ),
            any(RequestBody.class)
        );
    }

    @Test
    void storesJpegAndWebpBasedOnMagicBytes() {
        S3SellerProductImageStorage storage = storage();

        assertThat(storage.store(new MockMultipartFile(
            "image", "card.bin", "image/jpeg", bytes(0xff, 0xd8, 0xff, 0xe0, 0x01)
        ))).endsWith(".jpg");
        assertThat(storage.store(new MockMultipartFile(
            "image", "card.bin", "image/webp", bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 1)
        ))).endsWith(".webp");
    }

    @Test
    void rejectsSpoofedImageContent() {
        S3SellerProductImageStorage storage = storage();

        assertThatThrownBy(() -> storage.store(new MockMultipartFile(
            "image", "not-an-image.png", "image/png", "not a PNG".getBytes()
        )))
            .isInstanceOfSatisfying(InvalidSellerProductImageException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("IMAGE_TYPE_UNSUPPORTED")
            );
    }

    @Test
    void rejectsContentTypeThatDisagreesWithMagicBytes() {
        S3SellerProductImageStorage storage = storage();

        assertThatThrownBy(() -> storage.store(new MockMultipartFile(
            "image",
            "card.jpg",
            "image/jpeg",
            bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        )))
            .isInstanceOfSatisfying(InvalidSellerProductImageException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("IMAGE_TYPE_MISMATCH")
            );
    }

    @Test
    void rejectsImagesLargerThanFiveMebibytes() {
        S3SellerProductImageStorage storage = storage();
        byte[] oversized = new byte[(int) SellerProductImageValidator.MAX_IMAGE_SIZE_BYTES + 1];
        oversized[0] = (byte) 0xff;
        oversized[1] = (byte) 0xd8;
        oversized[2] = (byte) 0xff;

        assertThatThrownBy(() -> storage.store(
            new MockMultipartFile("image", "card.jpg", "image/jpeg", oversized)
        ))
            .isInstanceOfSatisfying(InvalidSellerProductImageException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("IMAGE_TOO_LARGE")
            );
    }

    @Test
    void ignoresExternalAndMalformedUrlsWhenDeleting() {
        S3SellerProductImageStorage storage = storage();

        storage.delete("https://untrusted.example.test/products/123.png");
        storage.delete("https://images.example.test/products/not-a-managed-key.png");

        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void deletesCreatedObjectWhenTransactionRollsBack() {
        S3SellerProductImageStorage storage = storage();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        String url = storage.store(new MockMultipartFile(
            "image",
            "card.webp",
            "image/webp",
            bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P')
        ));
        List<org.springframework.transaction.support.TransactionSynchronization> synchronizations =
            TransactionSynchronizationManager.getSynchronizations();

        synchronizations.forEach(sync ->
            sync.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK)
        );

        String key = url.substring("https://images.example.test/".length());
        verify(client).deleteObject(argThat((DeleteObjectRequest request) ->
            request.bucket().equals("tcg-product-images") && request.key().equals(key)
        ));
    }

    @Test
    void rejectsMissingS3Configuration() {
        SellerProductImageStorageProperties properties = new SellerProductImageStorageProperties(
            "http://s3mock:9090",
            "us-east-1",
            "",
            "https://images.example.test",
            true,
            "",
            ""
        );

        assertThatThrownBy(properties::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("OBJECT_STORAGE_BUCKET");
    }

    @Test
    void rejectsRelativeObjectStorageEndpoint() {
        SellerProductImageStorageProperties properties = new SellerProductImageStorageProperties(
            "s3mock:9090",
            "us-east-1",
            "tcg-product-images",
            "https://images.example.test",
            true,
            "access",
            "secret"
        );

        assertThatThrownBy(properties::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("OBJECT_STORAGE_ENDPOINT");
    }

    private S3SellerProductImageStorage storage() {
        return new S3SellerProductImageStorage(client, new SellerProductImageStorageProperties(
            "http://s3mock:9090",
            "us-east-1",
            "tcg-product-images",
            "https://images.example.test",
            true,
            "access",
            "secret"
        ));
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            bytes[index] = (byte) values[index];
        }
        return bytes;
    }
}

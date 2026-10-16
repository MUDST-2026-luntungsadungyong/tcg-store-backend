package com.mythix.tcg.seller.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class S3SellerProductImageStorageMetricsTests {

    private static final SellerProductImageStorageProperties PROPERTIES = new SellerProductImageStorageProperties(
        "http://s3mock:9090", "us-east-1", "bucket", "https://images.example.test", true, "key", "secret"
    );

    @Test
    void recordsUploadCounterAndHistogramWithOnlyBoundedTags() {
        S3Client client = mock(S3Client.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        S3SellerProductImageStorage storage = new S3SellerProductImageStorage(client, PROPERTIES, registry);

        storage.store(new MockMultipartFile("image", "card.png", "image/png", pngBytes()));

        assertThat(registry.get("tcg.storage.operations").tag("operation", "upload").tag("outcome", "success")
            .counter().count()).as("successful upload counter").isEqualTo(1);
        assertThat(registry.get("tcg.storage.operation.duration").tag("operation", "upload")
            .tag("outcome", "success").timer().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter ->
            assertThat(meter.getId().getTags()).extracting("key")
                .contains("operation", "outcome")
                .doesNotContain("objectKey", "url", "bucket", "customerId", "requestId"));
    }

    @Test
    void recordsStorageFailuresWithoutPuttingObjectIdentifiersInMetricTags() {
        S3Client client = mock(S3Client.class);
        doThrow(new IllegalStateException("provider failure"))
            .when(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        S3SellerProductImageStorage storage = new S3SellerProductImageStorage(client, PROPERTIES, registry);

        assertThatThrownBy(() -> storage.store(
            new MockMultipartFile("image", "card.png", "image/png", pngBytes())
        )).isInstanceOf(SellerProductImageStorageException.class);

        assertThat(registry.get("tcg.storage.operations").tag("operation", "upload").tag("outcome", "error")
            .counter().count()).isEqualTo(1);
        assertThat(registry.get("tcg.storage.operation.duration").tag("operation", "upload")
            .tag("outcome", "error").timer().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter ->
            assertThat(meter.getId().getTags()).extracting("value")
                .contains("upload", "error")
                .doesNotContain("provider failure", "bucket", "requestId"));
    }

    private static byte[] pngBytes() {
        return new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    }
}

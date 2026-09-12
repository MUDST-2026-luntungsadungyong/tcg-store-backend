package com.mythix.tcg.seller.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SellerProductImageStorageTests {

    private static final String BASE_URL = "https://cdn.example.test/";

    @TempDir
    Path temporaryDirectory;

    @Test
    void savesPngUsingGeneratedFilenameAndIgnoresOriginalFilename() throws IOException {
        SellerProductImageStorage storage = storage();
        byte[] png = bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01);
        MockMultipartFile image = new MockMultipartFile(
            "image",
            "../../unsafe name.png",
            "image/png",
            png
        );

        String url = storage.store(image);

        assertThat(url).matches(
            "https://cdn\\.example\\.test/uploads/products/"
                + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png"
        );
        String filename = url.substring(url.lastIndexOf('/') + 1);
        assertThat(Files.readAllBytes(temporaryDirectory.resolve(filename))).isEqualTo(png);
        assertThat(filename).doesNotContain("unsafe");
    }

    @Test
    void savesJpegAndWebpBasedOnMagicBytes() {
        SellerProductImageStorage storage = storage();
        MockMultipartFile jpeg = new MockMultipartFile(
            "image",
            "card.bin",
            "image/jpeg",
            bytes(0xff, 0xd8, 0xff, 0xe0, 0x01)
        );
        MockMultipartFile webp = new MockMultipartFile(
            "image",
            "card.bin",
            "image/webp",
            bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 1)
        );

        assertThat(storage.store(jpeg)).endsWith(".jpg");
        assertThat(storage.store(webp)).endsWith(".webp");
    }

    @Test
    void rejectsSpoofedContentThatOnlyDeclaresAnImageMimeType() {
        SellerProductImageStorage storage = storage();
        MockMultipartFile image = new MockMultipartFile(
            "image",
            "not-an-image.png",
            "image/png",
            "definitely not a PNG".getBytes()
        );

        assertThatThrownBy(() -> storage.store(image))
            .isInstanceOfSatisfying(InvalidSellerProductImageException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("IMAGE_TYPE_UNSUPPORTED")
            );
        assertThat(temporaryDirectory).isEmptyDirectory();
    }

    @Test
    void rejectsContentTypeThatDisagreesWithMagicBytes() {
        SellerProductImageStorage storage = storage();
        MockMultipartFile image = new MockMultipartFile(
            "image",
            "card.jpg",
            "image/jpeg",
            bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        );

        assertThatThrownBy(() -> storage.store(image))
            .isInstanceOfSatisfying(InvalidSellerProductImageException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("IMAGE_TYPE_MISMATCH")
            );
        assertThat(temporaryDirectory).isEmptyDirectory();
    }

    @Test
    void rejectsImagesLargerThanFiveMebibytes() {
        SellerProductImageStorage storage = storage();
        byte[] oversized = new byte[(int) SellerProductImageStorage.MAX_IMAGE_SIZE_BYTES + 1];
        oversized[0] = (byte) 0xff;
        oversized[1] = (byte) 0xd8;
        oversized[2] = (byte) 0xff;
        MockMultipartFile image = new MockMultipartFile("image", "card.jpg", "image/jpeg", oversized);

        assertThatThrownBy(() -> storage.store(image))
            .isInstanceOfSatisfying(InvalidSellerProductImageException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("IMAGE_TOO_LARGE")
            );
        assertThat(temporaryDirectory).isEmptyDirectory();
    }

    @Test
    void deletesOnlyManagedUuidNamedImages() throws IOException {
        SellerProductImageStorage storage = storage();
        String url = storage.store(new MockMultipartFile(
            "image",
            "card.png",
            "image/png",
            bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        ));
        String filename = url.substring(url.lastIndexOf('/') + 1);
        Path unrelated = temporaryDirectory.resolve("keep-me.png");
        Files.writeString(unrelated, "keep");

        storage.delete("https://cdn.example.test/uploads/products/keep-me.png");
        storage.delete("https://untrusted.example.test/uploads/products/" + filename);
        assertThat(temporaryDirectory.resolve(filename)).exists();
        storage.delete(url);

        assertThat(temporaryDirectory.resolve(filename)).doesNotExist();
        assertThat(unrelated).exists();
    }

    private SellerProductImageStorage storage() {
        return new SellerProductImageStorage(temporaryDirectory.toString(), BASE_URL);
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            bytes[index] = (byte) values[index];
        }
        return bytes;
    }
}

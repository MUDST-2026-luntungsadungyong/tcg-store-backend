package com.mythix.tcg.seller.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * Makes locally stored seller product images available over HTTP.
 */
@Configuration
@ConditionalOnProperty(
    name = "app.seller.product-images.storage",
    havingValue = "local",
    matchIfMissing = true
)
public class SellerProductImageWebConfig implements WebMvcConfigurer {

    private final Path storageRoot;

    public SellerProductImageWebConfig(
        @org.springframework.beans.factory.annotation.Value("${app.seller.product-images.storage-directory:uploads/products}")
        String storageDirectory
    ) {
        this.storageRoot = Path.of(storageDirectory).toAbsolutePath().normalize();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry
            .addResourceHandler("/uploads/products/**")
            .addResourceLocations(storageRoot.toUri().toASCIIString())
            .setCachePeriod(31_536_000)
            .resourceChain(true);
    }
}

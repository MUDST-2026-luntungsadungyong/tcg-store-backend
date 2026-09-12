package com.mythix.tcg.seller.api;

import com.mythix.tcg.seller.application.SellerProductImageStorage;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Makes locally stored seller product images available over HTTP.
 */
@Configuration
public class SellerProductImageWebConfig implements WebMvcConfigurer {

    private final SellerProductImageStorage imageStorage;

    public SellerProductImageWebConfig(SellerProductImageStorage imageStorage) {
        this.imageStorage = imageStorage;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry
            .addResourceHandler(SellerProductImageStorage.RESOURCE_HANDLER_PATTERN)
            .addResourceLocations(imageStorage.resourceLocation().toASCIIString())
            .setCachePeriod(31_536_000)
            .resourceChain(true);
    }
}

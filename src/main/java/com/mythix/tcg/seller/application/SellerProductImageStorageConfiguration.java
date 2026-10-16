package com.mythix.tcg.seller.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Configuration
@EnableConfigurationProperties(SellerProductImageStorageProperties.class)
public class SellerProductImageStorageConfiguration {

    @Bean(destroyMethod = "close")
    S3Client productImageS3Client(SellerProductImageStorageProperties properties) {
        properties.validate();
        S3ClientBuilder builder = S3Client.builder()
            .region(Region.of(properties.objectStorageRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                    properties.objectStorageAccessKey(),
                    properties.objectStorageSecretKey()
                )
            ))
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(properties.objectStoragePathStyle())
                .build())
            .httpClient(UrlConnectionHttpClient.builder().build());
        if (properties.objectStorageEndpoint() != null && !properties.objectStorageEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.objectStorageEndpoint()));
        }
        return builder.build();
    }

    @Bean
    SellerProductImageStore s3ProductImageStore(
        S3Client client,
        SellerProductImageStorageProperties properties,
        MeterRegistry meterRegistry
    ) {
        return new S3SellerProductImageStorage(client, properties, meterRegistry);
    }
}

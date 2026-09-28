package com.mythix.tcg.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Cors cors) {

    public AppProperties {
        cors = cors == null ? new Cors(null) : cors;
    }

    public record Cors(List<String> allowedOrigins) {
        public Cors {
            allowedOrigins = allowedOrigins == null || allowedOrigins.isEmpty()
                ? List.of(
                    "http://localhost:3000",
                    "http://localhost:5173",
                    "http://localhost:5174",
                    "http://127.0.0.1:3000",
                    "http://127.0.0.1:5173",
                    "http://127.0.0.1:5174"
                )
                : List.copyOf(allowedOrigins);
        }
    }
}

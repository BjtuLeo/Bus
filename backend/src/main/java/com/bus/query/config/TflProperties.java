package com.bus.query.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tfl")
public record TflProperties(
        String baseUrl,
        String appId,
        String appKey
) {
}

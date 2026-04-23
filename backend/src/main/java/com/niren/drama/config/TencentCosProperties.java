package com.niren.drama.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "niren.storage.cos")
public class TencentCosProperties {

    /** Enable COS as the public asset storage backend. */
    private boolean enabled;

    /** SecretId injected from environment variables. */
    private String secretId;

    /** SecretKey injected from environment variables. */
    private String secretKey;

    /** COS region, for example ap-beijing. */
    private String region = "ap-beijing";

    /** Bucket name including appId suffix. */
    private String bucket;

    /** Public HTTPS access domain of the bucket. */
    private String publicBaseUrl;

    /** Optional prefix inside the bucket. */
    private String basePath = "niren-drama";
}
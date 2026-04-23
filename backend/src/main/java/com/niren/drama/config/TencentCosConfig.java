package com.niren.drama.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.region.Region;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class TencentCosConfig {

    private final TencentCosProperties cosProperties;

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "niren.storage.cos", name = "enabled", havingValue = "true")
    public COSClient cosClient() {
        BasicCOSCredentials credentials = new BasicCOSCredentials(
                cosProperties.getSecretId(),
                cosProperties.getSecretKey());
        ClientConfig clientConfig = new ClientConfig(new Region(cosProperties.getRegion()));
        clientConfig.setHttpProtocol(HttpProtocol.https);
        return new COSClient(credentials, clientConfig);
    }
}
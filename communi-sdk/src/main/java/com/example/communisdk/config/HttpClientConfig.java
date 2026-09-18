package com.example.communisdk.config;

import lombok.RequiredArgsConstructor;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * HTTP 客户端配置: 提供带连接池的 Apache HttpClient5。
 * 该客户端作为 Feign 调用的底层传输层。
 */
@Configuration
@EnableConfigurationProperties(HttpClientProperties.class)
@RequiredArgsConstructor
public class HttpClientConfig {

    private final HttpClientProperties properties;

    @Bean(destroyMethod = "close")
    public CloseableHttpClient closeableHttpClient() {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(properties.getConnectTimeout())
                .setConnectionRequestTimeout(properties.getConnectTimeout())
                .setSocketTimeout(properties.getSocketTimeout())
                .build();

        return HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .setConnectionManagerShared(false)
                .setConnectionManager(poolingHttpClientConnectionManager())
                .evictIdleConnections(properties.getTimeToLive(), TimeUnit.SECONDS)
                .build();
    }

    @Bean
    public PoolingHttpClientConnectionManager poolingHttpClientConnectionManager() {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(properties.getMaxTotal());
        connectionManager.setDefaultMaxPerRoute(properties.getDefaultMaxPerRoute());
        return connectionManager;
    }
}

package com.example.serviceb.httpclient;

import lombok.RequiredArgsConstructor;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

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

package com.example.serviceb.httpclient;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "http.client")
public class HttpClientProperties {

    /**
     * 连接超时时间(毫秒)
     */
    private int connectTimeout = 5000;

    /**
     * 请求超时时间(毫秒)
     */
    private int socketTimeout = 10000;

    /**
     * 连接池最大连接数
     */
    private int maxTotal = 200;

    /**
     * 每个路由最大连接数
     */
    private int defaultMaxPerRoute = 50;

    /**
     * 连接存活时间(秒)
     */
    private int timeToLive = 60;

    /**
     * 是否启用连接池
     */
    private boolean enablePool = true;
}

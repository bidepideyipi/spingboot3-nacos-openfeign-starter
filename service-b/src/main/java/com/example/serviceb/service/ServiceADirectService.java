package com.example.serviceb.service;

import com.example.serviceb.UserDto;
import com.example.communisdk.http.HttpClientTemplate;
import com.example.communisdk.http.HttpResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 通过 HttpClient 直连 service-a(不使用 Feign)的服务。
 *
 * <p>与 {@link com.example.communisdk.client.ServiceACaller}(Feign + SDK)路径并列,
 * 用于演示"同一下游、不同客户端"的隔离方案:
 * <ul>
 *   <li>Feign 路径走隔离舱 {@code service-a}(SDK 的 ServiceACaller);</li>
 *   <li>本类走隔离舱 {@code service-a-http}(独立线程池,与 Feign 路径互不影响)。</li>
 * </ul>
 *
 * <p>线程池隔离、enabled 开关、blocked/error 兜底全部由 {@link HttpClientTemplate} 提供,
 * 本类只负责"拼 URL + 指定资源名",不写任何隔离代码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceADirectService {

    /** 资源名,对应 application.yml 中 thread-pool-bulkhead.instances.service-a-http */
    private static final String RESOURCE = "service-a-http";

    /** 直连 service-a(绕过 Feign/Nacos 发现,直接打 URL) */
    private static final String SERVICE_A_URL = "http://localhost:8081";

    private final HttpClientTemplate httpClientTemplate;

    /** 直连 service-a 的 /api/welcome */
    public HttpResponse<String> getWelcomeDirect() {
        log.info("HttpClient 直连 service-a: /api/welcome");
        return httpClientTemplate.get(SERVICE_A_URL + "/api/welcome", RESOURCE, String.class);
    }

    /** 直连 service-a 的 /api/users */
    public HttpResponse<UserDto[]> getUsersDirect() {
        log.info("HttpClient 直连 service-a: /api/users");
        return httpClientTemplate.get(SERVICE_A_URL + "/api/users", RESOURCE, UserDto[].class);
    }

    /** 直连 service-a 的 /api/users/{id} */
    public HttpResponse<UserDto> getUserByIdDirect(Long id) {
        log.info("HttpClient 直连 service-a: /api/users/{}", id);
        return httpClientTemplate.get(SERVICE_A_URL + "/api/users/" + id, RESOURCE, UserDto.class);
    }

    /**
     * 调用 service-a 的固定延迟慢响应接口 - 用于线程池隔离验证。
     * 所有请求延迟相同,便于并发打满 service-a-http 隔离舱(max-thread-pool-size)。
     *
     * @param ms 下游延迟毫秒数
     * @return 响应结果
     */
    public HttpResponse<String> callFixedSlowApi(int ms) {
        String url = SERVICE_A_URL + "/api/slow/fixed?ms=" + ms;
        log.info("HttpClient 直连 service-a 固定延迟: delay={}ms", ms);
        HttpResponse<String> response = httpClientTemplate.get(url, RESOURCE, String.class);
        if (response.isBlocked()) {
            log.warn("service-a-http 隔离舱已满,请求被拒绝: {}", response.getMessage());
        }
        return response;
    }
}

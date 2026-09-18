package com.example.serviceb.service;

import com.example.serviceb.UserDto;
import com.example.communisdk.http.HttpClientTemplate;
import com.example.communisdk.http.HttpResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 外部用户服务调用。
 *
 * <p>线程池隔离规则统一由 application.yml 中 resilience4j.thread-pool-bulkhead.* 配置，
 * 无需在代码中手动加载规则。资源名称(externalUserApi / slowApi / resetCounter)对应 yml 中
 * 的 thread-pool-bulkhead 实例。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalUserService {

    private final HttpClientTemplate httpClientTemplate;

    /**
     * 资源名称 - 外部用户服务API
     */
    private static final String RESOURCE_USER_API = "externalUserApi";

    /**
     * 资源名称 - 慢调用演示
     */
    private static final String RESOURCE_SLOW_API = "slowApi";

    /**
     * 外部服务地址(模拟其他集群)
     */
    private static final String EXTERNAL_SERVICE_URL = "http://localhost:8081/api/users";

    /**
     * 慢响应接口地址
     */
    private static final String SLOW_SERVICE_URL = "http://localhost:8081/api/slow/incremental";

    /**
     * 获取外部用户列表
     */
    public HttpResponse<List<UserDto>> fetchExternalUsers() {
        try {
            HttpResponse<UserDto[]> response = httpClientTemplate.get(
                    EXTERNAL_SERVICE_URL,
                    getHeaders(),
                    RESOURCE_USER_API,
                    UserDto[].class
            );

            if (response.isSuccess()) {
                List<UserDto> users = Arrays.asList(response.getData());
                return HttpResponse.success(response.getStatusCode(), users);
            } else {
                return handleFallback(response);
            }
        } catch (Exception e) {
            log.error("获取外部用户列表失败", e);
            return HttpResponse.error("系统异常: " + e.getMessage());
        }
    }

    /**
     * 调用慢响应接口（演示 Resilience4j 慢调用比例熔断）
     *
     * @return 响应结果
     */
    public HttpResponse<String> callSlowApi() {
        long startTime = System.currentTimeMillis();
        log.info("开始调用慢响应API...");

        HttpResponse<String> response = httpClientTemplate.get(
                SLOW_SERVICE_URL,
                getHeaders(),
                RESOURCE_SLOW_API,
                String.class
        );

        long duration = System.currentTimeMillis() - startTime;
        log.info("慢响应API调用完成, 耗时: {}ms, 状态: {}", duration, response.isSuccess() ? "成功" : response.getMessage());

        if (response.isDegraded() || response.isBlocked()) {
            return handleSlowFallback(response);
        }
        return response;
    }

    /**
     * 调用固定延迟慢响应接口 - 用于线程池隔离验证。
     * 所有请求延迟相同时间,便于并发打满隔离线程池(max-thread-pool-size)。
     *
     * @param ms 下游延迟毫秒数
     * @return 响应结果
     */
    public HttpResponse<String> callFixedSlowApi(int ms) {
        String url = "http://localhost:8081/api/slow/fixed?ms=" + ms;
        log.info("调用固定延迟慢响应API: delay={}ms", ms);
        HttpResponse<String> response = httpClientTemplate.get(url, getHeaders(), RESOURCE_SLOW_API, String.class);
        if (response.isBlocked()) {
            log.warn("隔离线程池已满,请求被拒绝: {}", response.getMessage());
        }
        return response;
    }

    /**
     * 重置Service-A的计数器
     */
    public HttpResponse<String> resetSlowCounter() {
        String url = "http://localhost:8081/api/slow/reset";
        return httpClientTemplate.get(url, getHeaders(), "resetCounter", String.class);
    }

    /**
     * 处理慢调用的降级
     */
    private HttpResponse<String> handleSlowFallback(HttpResponse<?> originalResponse) {
        if (originalResponse.isDegraded()) {
            log.warn("慢调用比例过高，服务已熔断降级: {}", originalResponse.getMessage());
            return HttpResponse.degraded("服务响应过慢，已触发熔断保护，请稍后重试");
        }

        if (originalResponse.isBlocked()) {
            log.warn("请求被限流: {}", originalResponse.getMessage());
            return HttpResponse.blocked("请求过于频繁，请稍后重试");
        }

        return HttpResponse.error(originalResponse.getMessage());
    }

    /**
     * 处理降级情况
     */
    private <T> HttpResponse<T> handleFallback(HttpResponse<?> originalResponse) {
        log.warn("服务降级/限流, 返回降级数据: message={}", originalResponse.getMessage());

        if (originalResponse.isDegraded()) {
            return HttpResponse.degraded("服务降级中, 返回缓存数据");
        }

        if (originalResponse.isBlocked()) {
            return HttpResponse.blocked("请求过于频繁, 请稍后重试");
        }

        return HttpResponse.error(originalResponse.getMessage());
    }

    /**
     * 构建通用请求头
     */
    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", "Microservice-HttpClient/1.0");
        return headers;
    }
}

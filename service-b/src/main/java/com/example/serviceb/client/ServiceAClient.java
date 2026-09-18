package com.example.serviceb.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * service-a 的 Feign 客户端(由消费方 service-b 自行定义)。
 *
 * <p>放在消费方是为了让 service-a 接口增删改时只改本文件,不需要修改 communi-sdk;
 * 且 SDK 不绑定业务 DTO。
 *
 * <p>所有方法经 {@link com.example.communisdk.client.ServiceACaller} 包装后,
 * 走名为 {@code "service-a"} 的线程池隔离舱。
 */
@FeignClient(name = "service-a")
public interface ServiceAClient {

    @GetMapping("/api/welcome")
    String getWelcome();

    @GetMapping("/api/health")
    String getHealth();

    /**
     * 调用 service-a 的固定延迟慢响应接口 - 用于线程池隔离验证。
     * 所有请求延迟相同,便于并发打满 service-a 隔离舱(max-thread-pool-size)。
     *
     * @param ms 下游延迟毫秒数
     */
    @GetMapping("/api/slow/fixed")
    String getSlowFixed(@RequestParam("ms") int ms);
}

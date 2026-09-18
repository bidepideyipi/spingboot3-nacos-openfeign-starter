package com.example.serviceb.controller;

import com.example.serviceb.service.ExternalUserService;
import com.example.communisdk.http.HttpResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/external")
@RequiredArgsConstructor
public class ExternalUserController {

    private final ExternalUserService externalUserService;

    // ========== 慢调用熔断演示接口 ==========

    /**
     * 调用慢响应API（演示 Resilience4j 慢调用比例熔断）
     *
     * 预期行为：
     * 1. 前几次请求正常返回（响应时间逐渐增加）
     * 2. 当慢调用比例超过阈值时，触发熔断
     * 3. 熔断期间返回降级响应
     * 4. 5秒后恢复 Half-Open 状态
     */
    @GetMapping("/slow/call")
    public HttpResponse<String> callSlowApi() {
        return externalUserService.callSlowApi();
    }

    /**
     * 重置Service-A的计数器（用于重新测试）
     */
    @PostMapping("/slow/reset")
    public HttpResponse<String> resetSlowCounter() {
        return externalUserService.resetSlowCounter();
    }

    /**
     * 调用固定延迟慢响应接口 - 用于线程池隔离验证。
     * 并发调用此接口,可观察隔离线程池打满后的拒绝行为。
     *
     * @param ms 下游延迟毫秒数,默认 5000
     */
    @GetMapping("/slow/fixed")
    public HttpResponse<String> callFixedSlowApi(@RequestParam(defaultValue = "5000") int ms) {
        return externalUserService.callFixedSlowApi(ms);
    }
}

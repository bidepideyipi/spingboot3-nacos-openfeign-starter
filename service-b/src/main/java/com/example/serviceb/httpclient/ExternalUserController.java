package com.example.serviceb.httpclient;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/external")
@RequiredArgsConstructor
public class ExternalUserController {

    private final ExternalUserService externalUserService;

    // ========== 慢调用熔断演示接口 ==========

    /**
     * 调用慢响应API（演示Sentinel慢调用比例熔断）
     *
     * 预期行为：
     * 1. 前几次请求正常返回（响应时间逐渐增加）
     * 2. 当慢调用比例超过50%时，触发熔断
     * 3. 熔断期间返回降级响应
     * 4. 10秒后恢复Half-Open状态
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
}

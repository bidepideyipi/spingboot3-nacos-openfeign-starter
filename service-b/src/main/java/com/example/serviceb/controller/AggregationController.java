package com.example.serviceb.controller;

import com.example.serviceb.service.UserAggregationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AggregationController {

    private final UserAggregationService userAggregationService;

    @GetMapping("/welcome")
    public String welcome() {
        log.info("调用 Service A welcome 接口");
        return userAggregationService.getWelcomeFromA();
    }

    @GetMapping("/health")
    public String health() {
        log.info("检查 Service A 健康状态");
        return "Service B is running! " + userAggregationService.getHealthFromA();
    }

    /**
     * 固定延迟慢调用(Feign 路径) - 用于线程池隔离验证。
     * 并发调用此接口,可观察 service-a 隔离舱打满后的拒绝行为。
     *
     * @param ms 下游延迟毫秒数,默认 2000
     */
    @GetMapping("/slow/fixed")
    public String slowFixed(@RequestParam(defaultValue = "2000") int ms) {
        return userAggregationService.callFixedSlowApi(ms);
    }
}

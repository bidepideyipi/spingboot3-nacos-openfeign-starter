package com.example.serviceb.controller;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 熔断器诊断接口 - 用于验证 Resilience4j CircuitBreaker 配置是否生效、查看运行时指标。
 *
 * <p>/api/circuitbreaker/info    打印每个实例的"配置值 + 当前状态"
 * <p>/api/circuitbreaker/metrics 打印每个实例的"运行时指标"(失败率/调用计数)
 *
 * <p>与 {@link BulkheadDiagnosticController} 配合使用:
 * 前者看隔离舱线程/队列,本类看熔断器失败率/状态。
 * Micrometer 指标同时通过 /actuator/metrics/resilience4j.circuitbreaker.* 暴露。
 */
@RestController
@RequestMapping("/api/circuitbreaker")
@RequiredArgsConstructor
public class CircuitBreakerDiagnosticController {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    /**
     * 配置值 + 当前状态验证 - 直接读取注册中心中各实例的配置,
     * 与 application.yml 中的配置项一一对照,验证配置是否正确加载。
     */
    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> result = new LinkedHashMap<>();
        Set<CircuitBreaker> breakers = circuitBreakerRegistry.getAllCircuitBreakers();
        for (CircuitBreaker cb : breakers) {
            CircuitBreakerConfig cfg = cb.getCircuitBreakerConfig();
            Map<String, Object> cfgMap = new LinkedHashMap<>();
            cfgMap.put("state", cb.getState());
            cfgMap.put("slidingWindowType", cfg.getSlidingWindowType());
            cfgMap.put("slidingWindowSize", cfg.getSlidingWindowSize());
            cfgMap.put("minimumNumberOfCalls", cfg.getMinimumNumberOfCalls());
            cfgMap.put("failureRateThreshold(%)", cfg.getFailureRateThreshold());
            cfgMap.put("slowCallRateThreshold(%)", cfg.getSlowCallRateThreshold());
            cfgMap.put("slowCallDurationThreshold", cfg.getSlowCallDurationThreshold().toString());
            cfgMap.put("permittedNumberOfCallsInHalfOpenState", cfg.getPermittedNumberOfCallsInHalfOpenState());
            result.put(cb.getName(), cfgMap);
        }
        return result;
    }

    /**
     * 运行时指标验证 - 读取熔断器的实时状态:
     * state                当前状态(CLOSED / OPEN / HALF_OPEN / FORCED_OPEN / DISABLED)
     * failureRate          当前窗口失败率(%)
     * slowCallRate         当前窗口慢调用率(%)
     * numberOfCalls         窗口内总调用数
     * numberOfSuccessfulCalls  窗口内成功调用数
     * numberOfFailedCalls   窗口内失败调用数
     * numberOfSlowCalls     窗口内慢调用数
     * bufferedCalls         已缓冲(纳入统计)的调用数
     */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (CircuitBreaker cb : circuitBreakerRegistry.getAllCircuitBreakers()) {
            CircuitBreaker.Metrics m = cb.getMetrics();
            Map<String, Object> mMap = new LinkedHashMap<>();
            mMap.put("state", cb.getState());
            mMap.put("failureRate(%)", m.getFailureRate());
            mMap.put("slowCallRate(%)", m.getSlowCallRate());
            mMap.put("numberOfBufferedCalls", m.getNumberOfBufferedCalls());
            mMap.put("numberOfSuccessfulCalls", m.getNumberOfSuccessfulCalls());
            mMap.put("numberOfFailedCalls", m.getNumberOfFailedCalls());
            mMap.put("numberOfSlowCalls", m.getNumberOfSlowCalls());
            mMap.put("numberOfSlowSuccessfulCalls", m.getNumberOfSlowSuccessfulCalls());
            mMap.put("numberOfSlowFailedCalls", m.getNumberOfSlowFailedCalls());
            mMap.put("numberOfNotPermittedCalls", m.getNumberOfNotPermittedCalls());
            result.put(cb.getName(), mMap);
        }
        return result;
    }
}

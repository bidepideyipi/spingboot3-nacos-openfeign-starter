package com.example.serviceb.controller;

import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 线程池隔离舱壁诊断接口 - 用于验证 Resilience4j 配置是否生效。
 *
 * <p>/api/bulkhead/info    打印每个实例的"配置值"(来自 yml)
 * <p>/api/bulkhead/metrics 打印每个实例的"运行时指标"(实际线程数/队列深度)
 */
@RestController
@RequestMapping("/api/bulkhead")
@RequiredArgsConstructor
public class BulkheadDiagnosticController {

    private final ThreadPoolBulkheadRegistry threadPoolBulkheadRegistry;

    /**
     * 配置值验证 - 直接读取注册中心中各实例的配置,
     * 与 application.yml 中的配置项一一对照,验证配置是否正确加载。
     */
    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> result = new LinkedHashMap<>();
        Set<ThreadPoolBulkhead> bulkheads = threadPoolBulkheadRegistry.getAllBulkheads();
        for (ThreadPoolBulkhead bh : bulkheads) {
            ThreadPoolBulkheadConfig cfg = bh.getBulkheadConfig();
            Map<String, Object> cfgMap = new LinkedHashMap<>();
            cfgMap.put("maxThreadPoolSize", cfg.getMaxThreadPoolSize());
            cfgMap.put("coreThreadPoolSize", cfg.getCoreThreadPoolSize());
            cfgMap.put("queueCapacity", cfg.getQueueCapacity());
            cfgMap.put("keepAliveDuration", cfg.getKeepAliveDuration().toString());
            result.put(bh.getName(), cfgMap);
        }
        return result;
    }

    /**
     * 运行时指标验证 - 读取隔离线程池的实时状态:
     * threadPoolSize     当前实际线程数(会从 core 增长到 max)
     * activeThreadCount  正在执行任务的线程数
     * availableThreadCount 可用线程数 = max - active
     * queueDepth         当前队列深度(queue-capacity=0 时恒为 0)
     * remainingQueueCapacity 剩余队列容量
     */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (ThreadPoolBulkhead bh : threadPoolBulkheadRegistry.getAllBulkheads()) {
            ThreadPoolBulkhead.Metrics m = bh.getMetrics();
            Map<String, Object> mMap = new LinkedHashMap<>();
            mMap.put("coreThreadPoolSize", m.getCoreThreadPoolSize());
            mMap.put("maxThreadPoolSize", m.getMaximumThreadPoolSize());
            mMap.put("threadPoolSize(实际)", m.getThreadPoolSize());
            mMap.put("activeThreadCount(忙碌)", m.getActiveThreadCount());
            mMap.put("availableThreadCount(可用)", m.getAvailableThreadCount());
            mMap.put("queueCapacity", m.getQueueCapacity());
            mMap.put("queueDepth(当前队列)", m.getQueueDepth());
            mMap.put("remainingQueueCapacity", m.getRemainingQueueCapacity());
            result.put(bh.getName(), mMap);
        }
        return result;
    }
}

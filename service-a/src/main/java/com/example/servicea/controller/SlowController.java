package com.example.servicea.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RestController
@RequestMapping("/api/slow")
public class SlowController {

    /**
     * 请求计数器 - 原子类保证线程安全
     */
    private final AtomicInteger requestCounter = new AtomicInteger(0);

    /**
     * 慢响应接口 - 每次请求响应时间递增
     * 第N次请求 = 延迟 N * 200ms
     *
     * @return 响应信息
     */
    @GetMapping("/incremental")
    public String incrementalSlow() {
        int count = requestCounter.incrementAndGet();
        int delayMs = count * 200;

        log.info("第 {} 次请求，延迟 {}ms", count, delayMs);

        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("睡眠被中断", e);
        }

        return String.format("第 %d 次请求完成 (延迟 %dms)", count, delayMs);
    }

    /**
     * 固定延迟慢响应接口 - 用于线程池隔离验证
     * 所有请求延迟相同时间,便于并发打满隔离线程池
     *
     * @param ms 延迟毫秒数,默认 5000
     * @return 响应信息
     */
    @GetMapping("/fixed")
    public String fixedSlow(@RequestParam(defaultValue = "5000") int ms) {
        log.info("固定延迟请求,延迟 {}ms", ms);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("睡眠被中断", e);
        }
        return String.format("固定延迟完成 (延迟 %dms)", ms);
    }

    /**
     * 重置计数器
     */
    @GetMapping("/reset")
    public String reset() {
        int oldCount = requestCounter.getAndSet(0);
        log.info("重置计数器，旧值: {}", oldCount);
        return "计数器已重置，之前请求次数: " + oldCount;
    }

    /**
     * 获取当前计数
     */
    @GetMapping("/count")
    public String getCount() {
        return "当前请求次数: " + requestCounter.get();
    }
}

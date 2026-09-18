package com.example.serviceb.service;

import com.example.communisdk.client.ServiceACaller;
import com.example.serviceb.client.ServiceAClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * service-a 聚合服务:通过 communi-sdk 的 {@link ServiceACaller} 调用 service-a(Feign 路径)。
 *
 * <p>所有对 service-a 的调用都经 {@link ServiceACaller#execute} 包装,
 * 自动获得线程池隔离保护(隔离舱 {@code "service-a"},注解在 SDK 的 ServiceACaller 类级别只写一次)。
 * service-a 接口增删改时,只需修改 {@link ServiceAClient},无需改 SDK。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAggregationService {

    /** 调用线程等待隔离线程池结果的最大时长 */
    private static final long CALL_TIMEOUT_SECONDS = 15;

    private final ServiceACaller serviceACaller;
    private final ServiceAClient serviceAClient;

    public String getWelcomeFromA() {
        log.info("调用 Service A 的 welcome 接口");
        return call(() -> serviceAClient.getWelcome());
    }

    public String getHealthFromA() {
        log.info("检查 Service A 健康状态");
        return call(() -> serviceAClient.getHealth());
    }

    /**
     * 调用 service-a 的固定延迟慢响应接口 - 用于线程池隔离验证(Feign 路径)。
     * 并发调用此接口,可观察 service-a 隔离舱打满后的拒绝行为。
     *
     * @param ms 下游延迟毫秒数
     */
    public String callFixedSlowApi(int ms) {
        log.info("Feign 调用 service-a 固定延迟: delay={}ms", ms);
        return call(() -> serviceAClient.getSlowFixed(ms));
    }

    // ---------- 私有工具:统一走 SDK 的线程池隔离 ----------

    private <T> T call(java.util.function.Supplier<T> feignCall) {
        try {
            return serviceACaller.execute(feignCall).get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("调用 service-a 失败: " + e.getMessage(), e);
        }
    }

}

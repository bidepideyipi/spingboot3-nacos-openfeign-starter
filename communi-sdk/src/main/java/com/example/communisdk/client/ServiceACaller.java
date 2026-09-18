package com.example.communisdk.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * service-a 的线程池隔离调用器(抽象封装)。
 *
 * <p>设计目标:
 * <ul>
 *   <li><b>注解只写一次</b>: 对 service-a 的所有调用统一走名为 {@code "service-a"} 的隔离舱,
 *       无需在每个方法上重复配置;</li>
 *   <li><b>与下游接口解耦</b>: 本类不持有任何 Feign 接口,只提供泛型 {@code execute(Supplier)};
 *       下游(service-a)新增/修改接口时,消费方在自己定义的 Feign 接口里调整即可,
 *       <b>无需修改本 SDK</b>;</li>
 *   <li><b>仅线程池隔离 + 可配置开关</b>: 本项目不使用熔断器,只靠线程池隔离感知下游压力
 *       (池+队列满时立即拒绝)。开关 {@code resilience4j.thread-pool-bulkhead.enabled}
 *       由 {@link BulkheadExecutor} 统一处理,本类不直接读取。</li>
 * </ul>
 *
 * <p>实现说明: 隔离提交逻辑抽到 {@link BulkheadExecutor} 复用(与消费方的 HttpClientTemplate 共享),
 * 本类只负责"指定资源名 + 透传 Supplier/Runnable"。
 *
 * <p>用法(消费方):
 * <pre>{@code
 * // 有返回值
 * String welcome = serviceACaller.execute(() -> serviceAClient.getWelcome()).get(15, TimeUnit.SECONDS);
 * // 无返回值
 * serviceACaller.executeVoid(() -> serviceAClient.deleteUser(id)).get(15, TimeUnit.SECONDS);
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceACaller {

    /** 资源名,对应 application.yml 中 thread-pool-bulkhead.instances.service-a */
    private static final String RESOURCE = "service-a";

    private final BulkheadExecutor bulkheadExecutor;

    /**
     * 在线程池隔离保护下执行一次有返回值的 service-a 调用。
     *
     * @param feignCall 封装了 Feign 调用的 Supplier(启用隔离时在隔离线程中求值)
     * @param <T>        返回类型
     * @return 执行完成后完成的 CompletableFuture;池+队列满时同步抛 BulkheadFullException
     */
    public <T> CompletableFuture<T> execute(Supplier<T> feignCall) {
        return bulkheadExecutor.submit(RESOURCE, feignCall);
    }

    /**
     * 查询 service-a 隔离舱的剩余可接纳容量(可用线程数 + 剩余队列容量)。
     *
     * <p>供消费方做背压预判:为 0 时不提交任务(如 MQ 消费者据此跳过 poll),
     * 把"池满"信号直接转化为"不再拉取",背压从隔离舱传到消费者再传回 broker。
     *
     * @return 剩余容量;隔离关闭时返回 {@link Integer#MAX_VALUE}
     */
    public int getRemainingCapacity() {
        return bulkheadExecutor.getRemainingCapacity(RESOURCE);
    }

}

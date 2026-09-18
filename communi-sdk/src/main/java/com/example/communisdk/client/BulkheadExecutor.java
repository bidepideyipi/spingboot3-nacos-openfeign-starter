package com.example.communisdk.client;

import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 线程池隔离执行器 —— 公共复用组件。
 *
 * <p>把"提交任务到隔离线程池 + enabled 开关"这层逻辑从各调用方抽出,供
 * {@link ServiceACaller}(Feign 路径)和消费方的 HttpClientTemplate(HttpClient 路径)复用,
 * 消除两处重复的隔离代码。
 *
 * <p>行为:
 * <ul>
 *   <li>{@code enabled=true}(默认): 按 {@code resource} 取隔离舱,任务在隔离线程池中执行;
 *       池+队列满时 {@code executeSupplier} 同步抛 {@link io.github.resilience4j.bulkhead.BulkheadFullException}
 *       (由调用方捕获并转为 blocked 响应)。</li>
 *   <li>{@code enabled=false}: 不走隔离池,任务在调用线程同步执行,直接返回已完成的 CompletableFuture。</li>
 * </ul>
 *
 * <p>开关属性: {@code resilience4j.thread-pool-bulkhead.enabled}(默认 true)。
 * 放在 SDK(而非消费方)是为了让 SDK 内的 ServiceACaller 和消费方的 HttpClientTemplate 都能依赖它
 * (依赖方向: 消费方 → SDK)。
 */
@Component
@RequiredArgsConstructor
public class BulkheadExecutor {

    private final ThreadPoolBulkheadRegistry threadPoolBulkheadRegistry;

    /**
     * 线程池隔离开关,与消费方 HttpClientTemplate 共用同一属性。
     */
    @Value("${resilience4j.thread-pool-bulkhead.enabled:true}")
    private boolean enabled;

    /**
     * 提交一个有返回值的任务到指定资源名的隔离舱。
     *
     * @param resource 隔离舱资源名(对应 yml 中 thread-pool-bulkhead.instances.&lt;name&gt;)
     * @param task     要执行的任务(启用隔离时在隔离线程中求值)
     * @param <T>       返回类型
     * @return 任务完成后完成的 CompletableFuture;池+队列满时 submit 同步抛 BulkheadFullException
     */
    public <T> CompletableFuture<T> submit(String resource, Supplier<T> task) {
        if (!enabled) {
            return CompletableFuture.completedFuture(task.get());
        }
        ThreadPoolBulkhead bulkhead = threadPoolBulkheadRegistry.bulkhead(resource);
        return bulkhead.executeSupplier(task).toCompletableFuture();
    }
}

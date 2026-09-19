package com.example.communisdk.client;

import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * 线程池隔离 + 熔断 执行器 —— 公共复用组件。
 *
 * <p>把"提交任务到隔离线程池 + 熔断器 + 双开关"这层逻辑从各调用方抽出,供
 * {@link ServiceACaller}(Feign 路径)和消费方的 HttpClientTemplate(HttpClient 路径)复用,
 * 消除两处重复的隔离代码。
 *
 * <p><b>装饰顺序: CircuitBreaker(外) → ThreadPoolBulkhead(内)</b>
 * <ul>
 *   <li>熔断打开 → {@code submit} 同步抛 {@link CallNotPermittedException},
 *       <b>不进池、不占线程</b>,保护本服务并发资源不被失败请求消耗;</li>
 *   <li>熔断关闭/半开 → 提交到隔离舱,池+队列满时 {@code executeSupplier} 同步抛
 *       {@link io.github.resilience4j.bulkhead.BulkheadFullException}
 *       (由调用方捕获并转为 blocked 响应)。</li>
 * </ul>
 *
 * <p>行为矩阵:
 * <ul>
 *   <li>{@code bulkhead.enabled=true, cb.enabled=true}(默认): CB 外、Bulkhead 内,完整链路;</li>
 *   <li>{@code bulkhead.enabled=false, cb.enabled=true}: 只熔断,不进池,任务在调用线程同步执行;</li>
 *   <li>{@code bulkhead.enabled=true, cb.enabled=false}: 只隔离,不熔断(原行为);</li>
 *   <li>两者都 false: 直通,调用线程同步执行。</li>
 * </ul>
 *
 * <p>开关属性:
 * <ul>
 *   <li>{@code resilience4j.thread-pool-bulkhead.enabled}(默认 true)</li>
 *   <li>{@code resilience4j.circuitbreaker.enabled}(默认 true)</li>
 * </ul>
 * 放在 SDK(而非消费方)是为了让 SDK 内的 ServiceACaller 和消费方的 HttpClientTemplate 都能依赖它
 * (依赖方向: 消费方 → SDK)。
 */
@Component
@RequiredArgsConstructor
public class BulkheadExecutor {

    private final ThreadPoolBulkheadRegistry threadPoolBulkheadRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    /**
     * 线程池隔离开关,与消费方 HttpClientTemplate 共用同一属性。
     */
    @Value("${resilience4j.thread-pool-bulkhead.enabled:true}")
    private boolean bulkheadEnabled;

    /**
     * 熔断器开关。
     */
    @Value("${resilience4j.circuitbreaker.enabled:true}")
    private boolean cbEnabled;

    /**
     * 提交一个有返回值的任务到指定资源名,经 CircuitBreaker(外) + ThreadPoolBulkhead(内) 装饰。
     *
     * <p>顺序语义:
     * <ul>
     *   <li>CB 打开 → 同步抛 {@link CallNotPermittedException},不进池、不占线程;</li>
     *   <li>CB 半开/关闭 → 提交到隔离舱,池+队列满抛 BulkheadFullException;</li>
     *   <li>任务执行结果(成功/异常)由 CB 的 whenComplete 回调记录,影响后续失败率统计。</li>
     * </ul>
     *
     * @param resource 隔离舱/熔断器资源名(对应 yml 中 instances.&lt;name&gt;)
     * @param task     要执行的任务(启用隔离时在隔离线程中求值)
     * @param <T>       返回类型
     * @return 任务完成后完成的 CompletableFuture;
     *         池+队列满时 submit 同步抛 BulkheadFullException;
     *         熔断打开时 submit 同步抛 CallNotPermittedException
     */
    public <T> CompletableFuture<T> submit(String resource, Supplier<T> task) {
        // 双开关全关: 直通
        if (!bulkheadEnabled && !cbEnabled) {
            return CompletableFuture.completedFuture(task.get());
        }

        // 内层 Supplier: 提交到隔离舱(返回 CompletionStage);隔离关闭则在调用线程同步执行
        Supplier<CompletionStage<T>> stageSupplier = () -> {
            if (!bulkheadEnabled) {
                return CompletableFuture.completedFuture(task.get());
            }
            ThreadPoolBulkhead bulkhead = threadPoolBulkheadRegistry.bulkhead(resource);
            return bulkhead.executeSupplier(task);   // CompletionStage<T>
        };

        // 外层 CB 装饰: CircuitBreaker.decorateCompletionStage 走异步路径:
        //   先同步 acquirePermission(熔断打开则抛 CallNotPermittedException),
        //   通过后才调用 stageSupplier.get()(即提交到池),并在 whenComplete 里记成功/失败。
        if (cbEnabled) {
            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(resource);
            stageSupplier = cb.decorateCompletionStage(stageSupplier);
        }
        return stageSupplier.get().toCompletableFuture();
    }

    /**
     * 查询指定资源名隔离舱的"剩余可接纳容量"。
     *
     * <p>剩余容量 = 可用线程数({@code availableThreadCount}) + 剩余队列容量({@code remainingQueueCapacity})。
     * 调用方可在提交前用它做背压预判:为 0 时不提交(如 MQ 消费者据此跳过 poll),
     * 避免提交后立即被拒绝(BulkheadFullException)再回退重投的无效往返。
     *
     * @param resource 隔离舱资源名
     * @return 剩余容量;bulkhead.enabled=false 时返回 {@link Integer#MAX_VALUE}(不隔离=无容量限制)
     */
    public int getRemainingCapacity(String resource) {
        if (!bulkheadEnabled) {
            return Integer.MAX_VALUE;
        }
        ThreadPoolBulkhead bulkhead = threadPoolBulkheadRegistry.bulkhead(resource);
        ThreadPoolBulkhead.Metrics m = bulkhead.getMetrics();
        return m.getAvailableThreadCount() + m.getRemainingQueueCapacity();
    }

    /**
     * 探测熔断器是否放行调用(不计数,仅探测)。
     *
     * <p>供消费方做背压预判:返回 false 时熔断打开,提交必定抛 {@link CallNotPermittedException},
     * 调用方应跳过本次提交(如 MQ 消费者据此跳过 poll,等熔断半开)。
     *
     * @param resource 熔断器资源名
     * @return true=熔断关闭/半开,可调用;false=熔断打开,不可调用;
     *         cb.enabled=false 时恒为 true(不熔断=永远放行)
     */
    public boolean isCallPermitted(String resource) {
        if (!cbEnabled) {
            return true;
        }
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(resource);
        return cb.tryAcquirePermission();   // 不计数,仅探测状态
    }
}

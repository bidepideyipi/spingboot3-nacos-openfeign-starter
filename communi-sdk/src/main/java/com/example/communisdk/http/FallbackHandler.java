package com.example.communisdk.http;

/**
 * 降级处理器接口。
 *
 * <p>消费方可实现此接口,在调用被隔离池拒绝或下游失败时返回自定义兜底值。
 *
 * @param <T> 降级返回类型
 */
@FunctionalInterface
public interface FallbackHandler<T> {

    /**
     * 当服务降级时执行的逻辑。
     *
     * @param message 降级原因
     * @return 降级返回值
     */
    T handle(String message);
}

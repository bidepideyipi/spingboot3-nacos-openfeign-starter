package com.example.serviceb.httpclient;

/**
 * 降级处理器接口
 */
@FunctionalInterface
public interface FallbackHandler<T> {

    /**
     * 当服务降级时执行的逻辑
     *
     * @param message 降级原因
     * @return 降级返回值
     */
    T handle(String message);
}

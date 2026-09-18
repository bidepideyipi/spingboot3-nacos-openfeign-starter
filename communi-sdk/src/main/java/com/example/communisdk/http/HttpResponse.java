package com.example.communisdk.http;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * HTTP 响应封装。
 *
 * <p>统一封装下游调用的结果,区分 success / blocked(隔离池满) / degraded(降级) / error,
 * 供 {@link HttpClientTemplate} 与消费方使用。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HttpResponse<T> {

    /** 是否成功 */
    private boolean success;

    /** HTTP 状态码 */
    private int statusCode;

    /** 响应数据 */
    private T data;

    /** 错误信息 */
    private String message;

    /** 是否降级 */
    private boolean degraded;

    /** 是否限流(隔离池满) */
    private boolean blocked;

    /** 创建成功响应 */
    public static <T> HttpResponse<T> success(int statusCode, T data) {
        return new HttpResponse<>(true, statusCode, data, null, false, false);
    }

    /** 创建错误响应 */
    public static <T> HttpResponse<T> error(String message) {
        return new HttpResponse<>(false, -1, null, message, false, false);
    }

    /** 创建错误响应(带状态码) */
    public static <T> HttpResponse<T> error(int statusCode, String message) {
        return new HttpResponse<>(false, statusCode, null, message, false, false);
    }

    /** 创建降级响应 */
    public static <T> HttpResponse<T> degraded(String message) {
        return new HttpResponse<>(false, -1, null, message, true, false);
    }

    /** 创建限流响应 */
    public static <T> HttpResponse<T> blocked(String message) {
        return new HttpResponse<>(false, -1, null, message, false, true);
    }
}

package com.example.serviceb.httpclient;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * HTTP响应封装
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HttpResponse<T> {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * HTTP状态码
     */
    private int statusCode;

    /**
     * 响应数据
     */
    private T data;

    /**
     * 错误信息
     */
    private String message;

    /**
     * 是否降级
     */
    private boolean degraded;

    /**
     * 是否限流
     */
    private boolean blocked;

    /**
     * 创建成功响应
     */
    public static <T> HttpResponse<T> success(int statusCode, T data) {
        return new HttpResponse<>(true, statusCode, data, null, false, false);
    }

    /**
     * 创建错误响应
     */
    public static <T> HttpResponse<T> error(String message) {
        return new HttpResponse<>(false, -1, null, message, false, false);
    }

    /**
     * 创建错误响应(带状态码)
     */
    public static <T> HttpResponse<T> error(int statusCode, String message) {
        return new HttpResponse<>(false, statusCode, null, message, false, false);
    }

    /**
     * 创建降级响应
     */
    public static <T> HttpResponse<T> degraded(String message) {
        return new HttpResponse<>(false, -1, null, message, true, false);
    }

    /**
     * 创建限流响应
     */
    public static <T> HttpResponse<T> blocked(String message) {
        return new HttpResponse<>(false, -1, null, message, false, true);
    }
}

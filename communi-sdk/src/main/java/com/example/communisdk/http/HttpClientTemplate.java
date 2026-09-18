package com.example.communisdk.http;

import com.example.communisdk.client.BulkheadExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * HTTP 请求模板，集成 Resilience4j 线程池隔离 (ThreadPoolBulkhead)。
 *
 * <p>下游调用在独立的 {@link io.github.resilience4j.bulkhead.ThreadPoolBulkhead} 线程池中执行:
 * <ul>
 *   <li>下游变慢时,只有隔离池中的线程在等待,本服务请求线程不会堆积;</li>
 *   <li>线程池满且队列满时立即抛 {@link BulkheadFullException},不阻塞调用线程;</li>
 *   <li>本项目不使用熔断器,仅靠线程池隔离感知下游压力。</li>
 * </ul>
 *
 * <p><b>隔离提交逻辑</b>(开关判断 + 提交到隔离池)复用 {@link BulkheadExecutor},
 * 与 Feign 路径的 {@code ServiceACaller} 共享同一份隔离代码。
 * 本类只保留 HTTP 专属逻辑:拼请求、执行、解析、超时/异常/blocked 兜底。
 *
 * 每个 resourceName 对应消费方 application.yml 中
 * {@code resilience4j.thread-pool-bulkhead.instances.<name>} 下同名的实例。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpClientTemplate {

    /** 调用线程等待隔离线程池结果的最大时长(硬性兜底,防止无限等待) */
    private static final long CALL_TIMEOUT_SECONDS = 15;

    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final BulkheadExecutor bulkheadExecutor;

    /**
     * GET 请求
     *
     * @param url          请求 URL
     * @param resourceName Resilience4j 资源名称(对应 thread-pool-bulkhead 实例名)
     * @param responseType 返回类型
     * @return 响应结果
     */
    public <T> HttpResponse<T> get(String url, String resourceName, Class<T> responseType) {
        HttpGet httpGet = new HttpGet(url);
        return execute(httpGet, resourceName, responseType);
    }

    /**
     * GET 请求(带请求头)
     */
    public <T> HttpResponse<T> get(String url, Map<String, String> headers, String resourceName, Class<T> responseType) {
        HttpGet httpGet = new HttpGet(url);
        headers.forEach(httpGet::addHeader);
        return execute(httpGet, resourceName, responseType);
    }

    /**
     * POST 请求(JSON)
     */
    public <T> HttpResponse<T> postJson(String url, Object body, String resourceName, Class<T> responseType) {
        try {
            HttpPost httpPost = new HttpPost(url);
            httpPost.setHeader("Content-Type", "application/json");
            String jsonBody = objectMapper.writeValueAsString(body);
            httpPost.setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8));
            return execute(httpPost, resourceName, responseType);
        } catch (Exception e) {
            log.error("POST请求构建失败: url={}", url, e);
            return HttpResponse.error("请求构建失败: " + e.getMessage());
        }
    }

    /**
     * POST 请求(带请求头)
     */
    public <T> HttpResponse<T> postJson(String url, Object body, Map<String, String> headers,
                                         String resourceName, Class<T> responseType) {
        try {
            HttpPost httpPost = new HttpPost(url);
            httpPost.setHeader("Content-Type", "application/json");
            headers.forEach(httpPost::addHeader);
            String jsonBody = objectMapper.writeValueAsString(body);
            httpPost.setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8));
            return execute(httpPost, resourceName, responseType);
        } catch (Exception e) {
            log.error("POST请求构建失败: url={}", url, e);
            return HttpResponse.error("请求构建失败: " + e.getMessage());
        }
    }

    /**
     * 执行请求(线程池隔离)。
     *
     * <p>隔离提交(开关 + 提交到隔离池)委托 {@link BulkheadExecutor};本方法负责 HTTP 专属的
     * 超时、异常、blocked 兜底。
     * <ul>
     *   <li>启用隔离且池+队列满: {@link BulkheadExecutor#submit} 同步抛 {@link BulkheadFullException}
     *       → 返回 blocked;</li>
     *   <li>关闭隔离: BulkheadExecutor 在调用线程同步执行,异常由 catch(Exception) 兜底;</li>
     *   <li>启用隔离且任务异步抛错: future.get() 抛 ExecutionException → 返回 error。</li>
     * </ul>
     */
    private <T> HttpResponse<T> execute(HttpRequestBase request, String resourceName, Class<T> responseType) {
        long startTime = System.currentTimeMillis();
        try {
            CompletableFuture<HttpResponse<T>> future = bulkheadExecutor.submit(
                    resourceName,
                    () -> doRequest(request, responseType, startTime)
            );
            return future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (BulkheadFullException e) {
            long rt = System.currentTimeMillis() - startTime;
            log.warn("隔离线程池已满,请求被拒绝: resource={}, url={}, rt={}ms", resourceName, request.getURI(), rt);
            return HttpResponse.blocked("下游压力过大,请求被拒绝: " + e.getMessage());
        } catch (TimeoutException e) {
            long rt = System.currentTimeMillis() - startTime;
            log.warn("请求等待超时: url={}, rt={}ms", request.getURI(), rt);
            return HttpResponse.error("请求超时: " + e.getMessage());
        } catch (ExecutionException e) {
            long rt = System.currentTimeMillis() - startTime;
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("请求执行异常: url={}, rt={}ms", request.getURI(), rt, cause);
            return HttpResponse.error("请求异常: " + cause.getMessage());
        } catch (Exception e) {
            // 覆盖:隔离关闭时 BulkheadExecutor 同步执行任务抛出的异常,及其它未预期异常
            long rt = System.currentTimeMillis() - startTime;
            log.error("未知异常: url={}, rt={}ms", request.getURI(), rt, e);
            return HttpResponse.error("未知异常: " + e.getMessage());
        }
    }

    /**
     * 实际执行 HTTP 请求并解析响应(在隔离线程池中运行,或隔离关闭时在调用线程运行)。
     */
    private <T> HttpResponse<T> doRequest(HttpRequestBase request, Class<T> responseType, long startTime) {
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            int statusCode = response.getStatusLine().getStatusCode();
            long rt = System.currentTimeMillis() - startTime;
            log.info("HTTP请求完成: url={}, statusCode={}, rt={}ms", request.getURI(), statusCode, rt);

            if (statusCode >= 200 && statusCode < 300) {
                T data = null;
                if (responseType != String.class && responseType != Void.class) {
                    data = objectMapper.readValue(responseBody, responseType);
                } else if (responseType == String.class) {
                    data = (T) responseBody;
                }
                return HttpResponse.success(statusCode, data);
            } else {
                throw new IOException("HTTP请求失败: statusCode=" + statusCode + ", body=" + responseBody);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

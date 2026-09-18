package com.example.serviceb.httpclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * HTTP 请求模板，仅集成 Resilience4j 线程池隔离 (ThreadPoolBulkhead)。
 *
 * <p>下游调用在独立的 {@link ThreadPoolBulkhead} 线程池中执行:
 * <ul>
 *   <li>下游变慢时,只有隔离池中的线程在等待,本服务请求线程不会堆积;</li>
 *   <li>线程池满且队列满时立即抛 {@link BulkheadFullException},不阻塞调用线程;</li>
 *   <li>本项目不使用熔断器,仅靠线程池隔离感知下游压力。</li>
 * </ul>
 * 每个 resourceName 对应 application.yml 中 resilience4j.thread-pool-bulkhead.instances
 * 下同名的实例。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpClientTemplate {

    /** 调用线程等待隔离线程池结果的最大时长(硬性兜底,防止无限等待) */
    private static final long CALL_TIMEOUT_SECONDS = 15;

    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ThreadPoolBulkheadRegistry threadPoolBulkheadRegistry;

    /**
     * 线程池隔离开关。
     * <ul>
     *   <li>{@code true}(默认): 走隔离线程池,池+队列满时立即拒绝(BulkheadFullException)。</li>
     *   <li>{@code false}: 调用线程直通,在当前线程同步执行下游调用,不隔离、不拒绝。</li>
     * </ul>
     * 由 {@code resilience4j.thread-pool-bulkhead.enabled} 控制。
     */
    @Value("${resilience4j.thread-pool-bulkhead.enabled:true}")
    private boolean bulkheadEnabled;

    /**
     * GET请求
     *
     * @param url          请求URL
     * @param resourceName Resilience4j 资源名称(对应 thread-pool-bulkhead 实例名)
     * @param responseType 返回类型
     * @return 响应结果
     */
    public <T> HttpResponse<T> get(String url, String resourceName, Class<T> responseType) {
        HttpGet httpGet = new HttpGet(url);
        return execute(httpGet, resourceName, responseType);
    }

    /**
     * GET请求(带请求头)
     */
    public <T> HttpResponse<T> get(String url, Map<String, String> headers, String resourceName, Class<T> responseType) {
        HttpGet httpGet = new HttpGet(url);
        headers.forEach(httpGet::addHeader);
        return execute(httpGet, resourceName, responseType);
    }

    /**
     * POST请求(JSON)
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
     * POST请求(带请求头)
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
     * 执行请求(线程池隔离)
     *
     * <p>调用链: 调用线程 -> 提交到隔离线程池 -> 实际HTTP请求
     * <p>隔离池满且队列满时 {@link BulkheadFullException} 立即返回限流响应。
     */
    private <T> HttpResponse<T> execute(HttpRequestBase request, String resourceName, Class<T> responseType) {
        long startTime = System.currentTimeMillis();

        // 隔离开关关闭:调用线程直通,不走隔离池
        if (!bulkheadEnabled) {
            try {
                return doRequest(request, responseType, startTime);
            } catch (RuntimeException e) {
                log.error("请求执行异常(线程池隔离已关闭): url={}", request.getURI(), e);
                return HttpResponse.error("请求异常: " + e.getMessage());
            }
        }

        ThreadPoolBulkhead bulkhead = threadPoolBulkheadRegistry.bulkhead(resourceName);

        try {
            // 实际调用在隔离线程池中执行(executeSupplier 池+队列满时同步抛 BulkheadFullException)
            CompletableFuture<HttpResponse<T>> future = bulkhead.executeSupplier(() ->
                    doRequest(request, responseType, startTime)
            ).toCompletableFuture();

            // 调用线程等待结果(有超时兜底,实际等待时长受 HTTP socket-timeout 约束)
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
            long rt = System.currentTimeMillis() - startTime;
            log.error("未知异常: url={}, rt={}ms", request.getURI(), rt, e);
            return HttpResponse.error("未知异常: " + e.getMessage());
        }
    }

    /**
     * 实际执行 HTTP 请求并解析响应(在隔离线程池中运行)。
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

package com.example.serviceb.httpclient;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class HttpClientTemplate {

    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * GET请求
     *
     * @param url          请求URL
     * @param resourceName Sentinel资源名称
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
     * 执行请求(集成Sentinel熔断)
     */
    private <T> HttpResponse<T> execute(HttpRequestBase request, String resourceName, Class<T> responseType) {
        Entry entry = null;
        long startTime = System.currentTimeMillis();
        try {
            // 1. 通过Sentinel进行资源保护
            entry = SphU.entry(resourceName, EntryType.OUT);
            long entryCreateTime = System.currentTimeMillis();
            log.debug("Sentinel Entry创建耗时: {}ms", entryCreateTime - startTime);

            // 2. 执行HTTP请求
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                int statusCode = response.getStatusLine().getStatusCode();
                long rt = System.currentTimeMillis() - startTime;

                log.info("HTTP请求成功: url={}, statusCode={}, rt={}ms", request.getURI(), statusCode, rt);

                // 3. 解析响应
                if (statusCode >= 200 && statusCode < 300) {
                    T data = null;
                    if (responseType != String.class && responseType != Void.class) {
                        data = objectMapper.readValue(responseBody, responseType);
                    } else if (responseType == String.class) {
                        data = (T) responseBody;
                    }
                    return HttpResponse.success(statusCode, data);
                } else {
                    return HttpResponse.error(statusCode, "HTTP请求失败: " + responseBody);
                }
            }
        } catch (BlockException e) {
            // 4. 熔断降级处理
            long rt = System.currentTimeMillis() - startTime;
            log.warn("请求被熔断: resource={}, url={}, rt={}ms, msg={}", resourceName, request.getURI(), rt, e.getMessage());
            if (e instanceof DegradeException) {
                return HttpResponse.degraded("服务降级: " + e.getMessage());
            }
            return HttpResponse.blocked("服务限流: " + e.getMessage());
        } catch (IOException e) {
            long rt = System.currentTimeMillis() - startTime;
            // 记录异常到Sentinel
            if (entry != null) {
                Tracer.traceEntry(e, entry);
            }
            log.error("HTTP请求异常: url={}, rt={}ms", request.getURI(), rt, e);
            return HttpResponse.error("请求异常: " + e.getMessage());
        } catch (Exception e) {
            long rt = System.currentTimeMillis() - startTime;
            // 记录异常到Sentinel
            if (entry != null) {
                Tracer.traceEntry(e, entry);
            }
            log.error("未知异常: url={}, rt={}ms", request.getURI(), rt, e);
            return HttpResponse.error("未知异常: " + e.getMessage());
        } finally {
            if (entry != null) {
                long totalRt = System.currentTimeMillis() - startTime;
                log.debug("Sentinel Entry退出, 总耗时: {}ms, resource={}", totalRt, resourceName);
                // Sentinel会自动追踪entry的持续时间作为RT
                entry.exit(1);
            }
        }
    }
}

package com.example.serviceb.httpclient;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.example.serviceb.UserDto;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalUserService {

    private final HttpClientTemplate httpClientTemplate;

    /**
     * 资源名称 - 外部用户服务API
     */
    private static final String RESOURCE_USER_API = "externalUserApi";

    /**
     * 资源名称 - 慢调用演示（重点！）
     */
    private static final String RESOURCE_SLOW_API = "slowApi";

    /**
     * 外部服务地址(模拟其他集群)
     */
    private static final String EXTERNAL_SERVICE_URL = "http://localhost:8081/api/users";

    /**
     * 慢响应接口地址
     */
    private static final String SLOW_SERVICE_URL = "http://localhost:8081/api/slow/incremental";

    @PostConstruct
    public void initSentinelRules() {
        System.out.println("========================================");
        System.out.println("初始化 Sentinel 规则...");
        System.out.println("========================================");

        initFlowRules();
        initDegradeRules();

        System.out.println("Sentinel 规则初始化完成!");
        System.out.println("熔断规则: " + DegradeRuleManager.getRules());
        System.out.println("========================================");
    }

    /**
     * 限流规则
     */
    private void initFlowRules() {
        List<FlowRule> flowRules = new ArrayList<>();

        // 用户API限流
        FlowRule userFlowRule = new FlowRule();
        userFlowRule.setResource(RESOURCE_USER_API);
        userFlowRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        userFlowRule.setCount(10);
        userFlowRule.setLimitApp("default");
        flowRules.add(userFlowRule);

        FlowRuleManager.loadRules(flowRules);
        log.info("限流规则加载完成: {}", flowRules);
    }

    /**
     * 熔断规则 - 一次性加载所有规则
     */
    private void initDegradeRules() {
        List<DegradeRule> degradeRules = new ArrayList<>();

        // 慢调用API熔断规则 - 慢调用比例（调整为更激进的配置测试）
        DegradeRule slowDegradeRule = new DegradeRule();
        slowDegradeRule.setResource(RESOURCE_SLOW_API);
        // 慢调用比例熔断: 0=异常数, 1=异常比例, 2=慢调用比例
        slowDegradeRule.setGrade(2);
        slowDegradeRule.setCount(100);  // 慢调用阈值100ms（降低阈值）
        slowDegradeRule.setTimeWindow(5);  // 熔断时长5秒
        slowDegradeRule.setSlowRatioThreshold(0.1);  // 慢调用比例10%（降低阈值）
        slowDegradeRule.setMinRequestAmount(2);  // 最小请求数2（降低阈值）
        slowDegradeRule.setStatIntervalMs(2000);  // 统计窗口2秒
        degradeRules.add(slowDegradeRule);

        // 一次性加载所有熔断规则
        DegradeRuleManager.loadRules(degradeRules);

        log.info("熔断规则加载完成: {}", degradeRules);
        System.out.println("慢调用熔断规则: 资源=" + RESOURCE_SLOW_API + ", 慢调用阈值=100ms, 比例=10%, 最小请求数=2");
    }

    /**
     * 获取外部用户列表
     */
    public HttpResponse<List<UserDto>> fetchExternalUsers() {
        try {
            HttpResponse<UserDto[]> response = httpClientTemplate.get(
                    EXTERNAL_SERVICE_URL,
                    getHeaders(),
                    RESOURCE_USER_API,
                    UserDto[].class
            );

            if (response.isSuccess()) {
                List<UserDto> users = Arrays.asList(response.getData());
                return HttpResponse.success(response.getStatusCode(), users);
            } else {
                return handleFallback(response);
            }
        } catch (Exception e) {
            log.error("获取外部用户列表失败", e);
            return HttpResponse.error("系统异常: " + e.getMessage());
        }
    }

    /**
     * 调用慢响应接口（演示Sentinel慢调用熔断）
     *
     * @return 响应结果
     */
    public HttpResponse<String> callSlowApi() {
        long startTime = System.currentTimeMillis();
        log.info("开始调用慢响应API...");

        HttpResponse<String> response = httpClientTemplate.get(
                SLOW_SERVICE_URL,
                getHeaders(),
                RESOURCE_SLOW_API,
                String.class
        );

        long duration = System.currentTimeMillis() - startTime;
        log.info("慢响应API调用完成, 耗时: {}ms, 状态: {}", duration, response.isSuccess() ? "成功" : response.getMessage());

        if (response.isDegraded() || response.isBlocked()) {
            return handleSlowFallback(response);
        }
        return response;
    }

    /**
     * 重置Service-A的计数器
     */
    public HttpResponse<String> resetSlowCounter() {
        String url = "http://localhost:8081/api/slow/reset";
        return httpClientTemplate.get(url, getHeaders(), "resetCounter", String.class);
    }

    /**
     * 处理慢调用的降级
     */
    private HttpResponse<String> handleSlowFallback(HttpResponse<?> originalResponse) {
        if (originalResponse.isDegraded()) {
            log.warn("慢调用比例过高，服务已熔断降级: {}", originalResponse.getMessage());
            return HttpResponse.degraded("服务响应过慢，已触发熔断保护，请稍后重试");
        }

        if (originalResponse.isBlocked()) {
            log.warn("请求被限流: {}", originalResponse.getMessage());
            return HttpResponse.blocked("请求过于频繁，请稍后重试");
        }

        return HttpResponse.error(originalResponse.getMessage());
    }

    /**
     * 处理降级情况
     */
    private <T> HttpResponse<T> handleFallback(HttpResponse<?> originalResponse) {
        log.warn("服务降级/限流, 返回降级数据: message={}", originalResponse.getMessage());

        if (originalResponse.isDegraded()) {
            return HttpResponse.degraded("服务降级中, 返回缓存数据");
        }

        if (originalResponse.isBlocked()) {
            return HttpResponse.blocked("请求过于频繁, 请稍后重试");
        }

        return HttpResponse.error(originalResponse.getMessage());
    }

    /**
     * 构建通用请求头
     */
    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", "Microservice-HttpClient/1.0");
        return headers;
    }
}

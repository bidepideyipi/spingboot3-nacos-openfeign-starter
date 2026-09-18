package com.example.serviceb.service;

import com.example.communisdk.client.ServiceACaller;
import com.example.serviceb.UserDto;
import com.example.serviceb.client.ServiceAClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 用户聚合服务:通过 communi-sdk 的 {@link ServiceACaller} 调用 service-a。
 *
 * <p>所有对 service-a 的调用都经 {@link ServiceACaller#execute} 包装,
 * 自动获得"熔断 + 线程池隔离"保护(注解在 SDK 的 ServiceACaller 类级别只写一次)。
 * service-a 接口增删改时,只需修改 {@link ServiceAClient},无需改 SDK。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAggregationService {

    /** 调用线程等待隔离线程池结果的最大时长 */
    private static final long CALL_TIMEOUT_SECONDS = 15;

    private final ServiceACaller serviceACaller;
    private final ServiceAClient serviceAClient;

    public String getWelcomeFromA() {
        log.info("调用 Service A 的 welcome 接口");
        String result = call(() -> serviceAClient.getWelcome());
        return "Service B 调用结果: " + result;
    }

    public String getHealthFromA() {
        log.info("检查 Service A 健康状态");
        return call(() -> serviceAClient.getHealth());
    }

    public List<UserDto> getAllUsers() {
        log.info("从 Service A 获取所有用户");
        return call(() -> serviceAClient.getAllUsers());
    }

    public UserDto getUserById(Long id) {
        log.info("从 Service A 获取用户: id={}", id);
        return call(() -> serviceAClient.getUserById(id));
    }

    public UserDto createUser(UserDto user) {
        log.info("通过 Service A 创建用户: {}", user);
        return call(() -> serviceAClient.createUser(user));
    }

    public UserDto updateUser(Long id, UserDto user) {
        log.info("通过 Service A 更新用户: id={}, user={}", id, user);
        return call(() -> serviceAClient.updateUser(id, user));
    }

    public void deleteUser(Long id) {
        log.info("通过 Service A 删除用户: id={}", id);
        callVoid(() -> serviceAClient.deleteUser(id));
    }

    public String getUserSummary() {
        List<UserDto> users = call(() -> serviceAClient.getAllUsers());
        return String.format("Service A 当前共有 %d 个用户", users.size());
    }

    // ---------- 私有工具:统一走 SDK 的熔断 + 线程池隔离 ----------

    private <T> T call(java.util.function.Supplier<T> feignCall) {
        try {
            return serviceACaller.execute(feignCall).get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("调用 service-a 失败: " + e.getMessage(), e);
        }
    }

    private void callVoid(Runnable feignCall) {
        try {
            serviceACaller.executeVoid(feignCall).get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("调用 service-a 失败: " + e.getMessage(), e);
        }
    }
}

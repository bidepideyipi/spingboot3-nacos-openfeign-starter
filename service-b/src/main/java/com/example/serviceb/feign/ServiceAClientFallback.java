package com.example.serviceb.feign;

import com.example.serviceb.UserDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * ServiceAClient 的降级处理。
 *
 * <p>使用 {@link FallbackFactory} 而非简单的 fallback 类，可以在降级时拿到触发熔断的异常，
 * 便于记录日志和定位问题。Resilience4j 在熔断/超时/异常时会自动调用这里的方法。
 */
@Slf4j
@Component
public class ServiceAClientFallback implements FallbackFactory<ServiceAClient> {

    @Override
    public ServiceAClient create(Throwable cause) {
        log.error("调用 service-a 触发降级, reason: {}", cause.getMessage(), cause);

        return new ServiceAClient() {
            @Override
            public String getWelcome() {
                return "降级: Service A 不可用 (welcome)";
            }

            @Override
            public String getHealth() {
                return "DOWN";
            }

            @Override
            public List<UserDto> getAllUsers() {
                return Collections.emptyList();
            }

            @Override
            public UserDto getUserById(Long id) {
                return null;
            }

            @Override
            public UserDto createUser(UserDto user) {
                return null;
            }

            @Override
            public UserDto updateUser(Long id, UserDto user) {
                return null;
            }

            @Override
            public void deleteUser(Long id) {
                log.warn("删除用户降级: id={}, 操作被忽略", id);
            }
        };
    }
}

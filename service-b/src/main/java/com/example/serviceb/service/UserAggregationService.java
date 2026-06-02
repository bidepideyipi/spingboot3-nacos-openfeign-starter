package com.example.serviceb.service;

import com.example.serviceb.UserDto;
import com.example.serviceb.feign.ServiceAClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserAggregationService {

    private final ServiceAClient serviceAClient;

    public String getWelcomeFromA() {
        log.info("调用 Service A 的 welcome 接口");
        String result = serviceAClient.getWelcome();
        return "Service B 调用结果: " + result;
    }

    public String getHealthFromA() {
        log.info("检查 Service A 健康状态");
        return serviceAClient.getHealth();
    }

    public List<UserDto> getAllUsers() {
        log.info("从 Service A 获取所有用户");
        return serviceAClient.getAllUsers();
    }

    public UserDto getUserById(Long id) {
        log.info("从 Service A 获取用户: id={}", id);
        return serviceAClient.getUserById(id);
    }

    public UserDto createUser(UserDto user) {
        log.info("通过 Service A 创建用户: {}", user);
        return serviceAClient.createUser(user);
    }

    public UserDto updateUser(Long id, UserDto user) {
        log.info("通过 Service A 更新用户: id={}, user={}", id, user);
        return serviceAClient.updateUser(id, user);
    }

    public void deleteUser(Long id) {
        log.info("通过 Service A 删除用户: id={}", id);
        serviceAClient.deleteUser(id);
    }

    public String getUserSummary() {
        List<UserDto> users = serviceAClient.getAllUsers();
        return String.format("Service A 当前共有 %d 个用户", users.size());
    }
}

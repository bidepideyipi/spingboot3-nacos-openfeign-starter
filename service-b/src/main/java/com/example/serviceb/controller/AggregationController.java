package com.example.serviceb.controller;

import com.example.serviceb.UserDto;
import com.example.serviceb.service.UserAggregationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AggregationController {

    private final UserAggregationService userAggregationService;

    @GetMapping("/welcome")
    public String welcome() {
        log.info("调用 Service A welcome 接口");
        return userAggregationService.getWelcomeFromA();
    }

    @GetMapping("/health")
    public String health() {
        log.info("检查 Service A 健康状态");
        return "Service B is running! " + userAggregationService.getHealthFromA();
    }

    @GetMapping("/users/summary")
    public String getUserSummary() {
        return userAggregationService.getUserSummary();
    }

    @GetMapping("/users")
    public List<UserDto> getAllUsers() {
        log.info("获取所有用户（通过 Service A）");
        return userAggregationService.getAllUsers();
    }

    @GetMapping("/users/{id}")
    public UserDto getUserById(@PathVariable Long id) {
        log.info("获取用户: id={}", id);
        return userAggregationService.getUserById(id);
    }

    @PostMapping("/users")
    public UserDto createUser(@RequestBody UserDto user) {
        log.info("创建用户: {}", user);
        return userAggregationService.createUser(user);
    }

    @PutMapping("/users/{id}")
    public UserDto updateUser(@PathVariable Long id, @RequestBody UserDto user) {
        log.info("更新用户: id={}, user={}", id, user);
        return userAggregationService.updateUser(id, user);
    }

}

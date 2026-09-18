package com.example.serviceb.client;

import com.example.serviceb.UserDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * service-a 的 Feign 客户端(由消费方 service-b 自行定义)。
 *
 * <p>放在消费方是为了让 service-a 接口/DTO 增删改时只改本文件,
 * 不需要修改 communi-sdk;且 SDK 不绑定业务 DTO。
 */
@FeignClient(name = "service-a")
public interface ServiceAClient {

    @GetMapping("/api/welcome")
    String getWelcome();

    @GetMapping("/api/health")
    String getHealth();

    @GetMapping("/api/users")
    List<UserDto> getAllUsers();

    @GetMapping("/api/users/{id}")
    UserDto getUserById(@PathVariable("id") Long id);

    @PostMapping("/api/users")
    UserDto createUser(@RequestBody UserDto user);

    @PutMapping("/api/users/{id}")
    UserDto updateUser(@PathVariable("id") Long id, @RequestBody UserDto user);

    @DeleteMapping("/api/users/{id}")
    void deleteUser(@PathVariable("id") Long id);
}

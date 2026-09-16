package com.example.serviceb.feign;

import com.example.serviceb.UserDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@FeignClient(name = "service-a", fallbackFactory = ServiceAClientFallback.class)
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

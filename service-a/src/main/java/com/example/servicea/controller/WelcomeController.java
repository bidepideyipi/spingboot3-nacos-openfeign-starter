package com.example.servicea.controller;

import com.example.servicea.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class WelcomeController {

    private final UserService userService;

    @GetMapping("/api/welcome")
    public String welcome() {
        return userService.getWelcome();
    }

    @GetMapping("/api/health")
    public String health() {
        return "Service A is running!";
    }
}

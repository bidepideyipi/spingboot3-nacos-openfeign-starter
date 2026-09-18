package com.example.serviceb.controller;

import com.example.serviceb.UserDto;
import com.example.communisdk.http.HttpResponse;
import com.example.serviceb.service.ServiceADirectService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HttpClient 直连 service-a 的演示接口(不使用 Feign)。
 *
 * <p>与 Feign 路径({@code /api/users} 等经 UserAggregationService)并列,
 * 用于验证隔离舱 {@code service-a-http} 的独立线程池隔离效果。
 */
@RestController
@RequestMapping("/api/service-a-direct")
@RequiredArgsConstructor
public class ServiceADirectController {

    private final ServiceADirectService serviceADirectService;

    @GetMapping("/welcome")
    public HttpResponse<String> welcome() {
        return serviceADirectService.getWelcomeDirect();
    }

    @GetMapping("/users")
    public HttpResponse<UserDto[]> users() {
        return serviceADirectService.getUsersDirect();
    }

    @GetMapping("/users/{id}")
    public HttpResponse<UserDto> userById(@PathVariable Long id) {
        return serviceADirectService.getUserByIdDirect(id);
    }

    /** 固定延迟慢调用,用于线程池隔离验证 */
    @GetMapping("/slow/fixed")
    public HttpResponse<String> slowFixed(@RequestParam(defaultValue = "2000") int ms) {
        return serviceADirectService.callFixedSlowApi(ms);
    }
}

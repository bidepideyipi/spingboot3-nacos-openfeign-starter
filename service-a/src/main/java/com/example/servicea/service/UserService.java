package com.example.servicea.service;

import com.example.servicea.UserDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class UserService {

    private final Map<Long, UserDto> users = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    static {
        // 初始化一些测试数据
        Map<Long, UserDto> initialUsers = new ConcurrentHashMap<>();
        initialUsers.put(1L, new UserDto(1L, "张三", "zhangsan@example.com", 25));
        initialUsers.put(2L, new UserDto(2L, "李四", "lisi@example.com", 30));
        initialUsers.put(3L, new UserDto(3L, "王五", "wangwu@example.com", 28));
    }

    public List<UserDto> getAllUsers() {
        return new ArrayList<>(users.values());
    }

    public UserDto getUserById(Long id) {
        return users.get(id);
    }

    public UserDto createUser(UserDto user) {
        Long id = idGenerator.getAndIncrement();
        user.setId(id);
        users.put(id, user);
        return user;
    }

    public UserDto updateUser(Long id, UserDto user) {
        user.setId(id);
        users.put(id, user);
        return user;
    }

    public void deleteUser(Long id) {
        users.remove(id);
    }

    public String getWelcome() {
        return "欢迎来到 Service A - 用户服务";
    }
}

package com.agi.assistant.service.impl;

import com.agi.assistant.mapper.UserMapper;
import com.agi.assistant.model.dto.LoginRequest;
import com.agi.assistant.model.dto.RegisterRequest;
import com.agi.assistant.model.entity.User;
import com.agi.assistant.service.AuthService;
import com.agi.assistant.service.security.AuthenticationException;
import com.agi.assistant.service.security.JwtService;
import com.agi.assistant.service.security.PasswordHasher;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /** 用户名最大长度，与 user 表列宽一致 */
    private static final int MAX_USERNAME_LENGTH = 64;

    private final UserMapper userMapper;
    private final PasswordHasher passwordHasher;
    private final JwtService jwtService;

    @Override
    @Transactional
    public User register(RegisterRequest request) {
        String username = normalizeUsername(request.getUsername());

        // 唯一性检查。注意：这里先查一次是为了给出友好报错，
        // 真正的并发兜底是 user 表的 UNIQUE 约束（两请求同时通过检查时由 DB 拒绝）。
        User existing = findByUsername(username);
        if (existing != null) {
            throw new IllegalArgumentException("用户名已被占用: " + username);
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordHasher.hash(request.getPassword()));
        user.setNickname(request.getNickname() == null || request.getNickname().isBlank()
                ? username : request.getNickname().trim());
        user.setEmail(request.getEmail());
        user.setStatus(1);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());

        userMapper.insert(user);
        log.info("用户注册成功: id={}, username={}", user.getId(), username);

        return sanitize(user);
    }

    @Override
    public Map<String, Object> login(LoginRequest request) {
        String username = normalizeUsername(request.getUsername());

        User user = findByUsername(username);

        // 用户不存在与密码错误返回同一条消息：避免通过错误信息枚举出哪些用户名存在
        if (user == null || !passwordHasher.matches(request.getPassword(), user.getPassword())) {
            log.warn("登录失败: username={}", username);
            throw new AuthenticationException("用户名或密码错误");
        }

        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new AuthenticationException("该账号已被禁用");
        }

        String token = jwtService.generateToken(user.getId(), user.getUsername());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token", token);
        response.put("tokenType", "Bearer");
        response.put("expiresInHours", jwtService.getExpireHours());
        response.put("user", sanitize(user));

        log.info("登录成功: id={}, username={}", user.getId(), username);
        return response;
    }

    @Override
    public User getCurrentUser(Long userId) {
        if (userId == null) {
            throw new AuthenticationException("用户未认证");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            // token 有效但用户已被删除：这属于认证态失效，不是「资源不存在」
            throw new AuthenticationException("用户不存在: " + userId);
        }
        return sanitize(user);
    }

    private User findByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)
                .last("LIMIT 1"));
    }

    private String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        String trimmed = username.trim();
        if (trimmed.length() > MAX_USERNAME_LENGTH) {
            throw new IllegalArgumentException("用户名过长");
        }
        return trimmed;
    }

    /**
     * 剥离密码字段。
     * <p>
     * 必须显式置空 —— User 实体带 password 字段，直接返回会让哈希串
     * 出现在接口响应里（虽然是哈希，仍属于不该外泄的凭据材料）。
     */
    private User sanitize(User user) {
        user.setPassword(null);
        return user;
    }
}

package com.agi.assistant.service;

import com.agi.assistant.model.dto.LoginRequest;
import com.agi.assistant.model.dto.RegisterRequest;
import com.agi.assistant.model.entity.User;

import java.util.Map;

/**
 * 认证服务。
 */
public interface AuthService {

    /**
     * 注册新用户。
     *
     * @param request 注册请求
     * @return 新建用户（不含密码）
     */
    User register(RegisterRequest request);

    /**
     * 登录并签发 token。
     *
     * @param request 登录请求
     * @return 含 token / 过期时间 / 用户信息的响应体
     */
    Map<String, Object> login(LoginRequest request);

    /**
     * 查询当前登录用户。
     *
     * @param userId 用户 ID
     * @return 用户信息（不含密码）
     */
    User getCurrentUser(Long userId);
}

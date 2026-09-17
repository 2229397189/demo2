package com.agi.assistant.controller;

import com.agi.assistant.model.dto.LoginRequest;
import com.agi.assistant.model.dto.RegisterRequest;
import com.agi.assistant.model.entity.User;
import com.agi.assistant.model.vo.Result;
import com.agi.assistant.service.AuthService;
import com.agi.assistant.service.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证接口。
 * <p>
 * 此前项目没有任何登录入口，所有业务接口靠 {@code X-User-Id} 请求头认定身份 ——
 * 也就是说「我是谁」由客户端自己声明。本控制器补齐真正的登录链路：
 * 注册 → 登录换 JWT → 后续请求带 Bearer token。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "认证接口")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @Operation(summary = "注册", description = "创建新用户，用户名全局唯一")
    public Result<User> register(@Valid @RequestBody RegisterRequest request) {
        // 业务性失败（重名、格式非法）由 AuthServiceImpl 抛 IllegalArgumentException，
        // 交给 GlobalExceptionHandler 统一映射为 HTTP 400 —— 不再在这里 return Result.fail(400)，
        // 那种写法只会改响应体里的 code，HTTP 状态码仍是 200。
        return Result.ok(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "登录", description = "校验用户名密码并签发 JWT")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        // 凭据错误抛 AuthenticationException → HTTP 401
        return Result.ok(authService.login(request));
    }

    @GetMapping("/me")
    @Operation(summary = "当前用户", description = "根据请求携带的 token 返回当前登录用户信息")
    public Result<User> me() {
        return Result.ok(authService.getCurrentUser(UserContext.getUserId()));
    }

    @PostMapping("/logout")
    @Operation(summary = "登出", description = "JWT 为无状态令牌，服务端不持有会话；此处仅作语义占位，客户端应丢弃本地 token")
    public Result<Void> logout() {
        // 无状态 JWT 的登出 = 客户端删除 token。若需要服务端强制失效，
        // 需引入 token 黑名单（Redis）—— 当前未实现，这里如实说明而不是假装登出了。
        log.info("用户 {} 请求登出（无状态 token，客户端丢弃即可）", UserContext.getUserId());
        return Result.ok();
    }
}

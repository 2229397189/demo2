package com.agi.assistant.service.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;

/**
 * 认证拦截器。
 * <p>
 * 修复说明：这套接口此前依赖 {@code X-User-Id} 请求头（控制器里还写了
 * {@code defaultValue="1"}），任何人改一下请求头就能冒充任意用户读写数据。
 * 现在身份只能来自服务端签发的 JWT；只有在显式关闭鉴权（本地调试）时，
 * 才允许回退到 X-User-Id，并且会打 WARN 提醒。
 * <p>
 * 认证逻辑（对应「工具路由协议」里的『会改外部数据/高风险动作单独查授权』）：
 * <ol>
 *   <li>有 Bearer token 且校验通过 → 以 token 中的 userId 为准，忽略请求头（防止伪造）</li>
 *   <li>token 无效/过期 → 401，绝不静默降级</li>
 *   <li>无 token 且 auth-enabled=false → 回退 X-User-Id（开发便利），并记 WARN</li>
 *   <li>无 token 且 auth-enabled=true → 401</li>
 * </ol>
 */
@Slf4j
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtService jwtService;
    private final AuditService auditService;

    /** 是否强制要求登录 */
    @Value("${app.security.auth-enabled:true}")
    private boolean authEnabled;

    public AuthInterceptor(JwtService jwtService,
                           @Lazy AuditService auditService) {
        this.jwtService = jwtService;
        this.auditService = auditService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {

        // 预检请求直接放行：CORS 预检不带 Authorization，拦掉会让浏览器侧全部失败
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = jwtService.resolveToken(request.getHeader("Authorization"));

        if (token != null) {
            Long userId = jwtService.extractUserId(token);
            if (userId != null) {
                UserContext.setUserId(userId);
                return true;
            }

            // 带了 token 但无效：明确拒绝，不回退到请求头。
            // 回退会让「拿着过期 token 的客户端」意外获得 X-User-Id 声明出的身份。
            log.debug("无效或过期的 token，请求被拒: {}", request.getRequestURI());
            reject(response, "TOKEN_INVALID", "登录状态已失效，请重新登录");
            auditAuthFailure(request, "TOKEN_INVALID");
            return false;
        }

        if (authEnabled) {
            log.debug("缺少 Authorization 头，请求被拒: {}", request.getRequestURI());
            reject(response, "UNAUTHORIZED", "请先登录");
            auditAuthFailure(request, "MISSING_TOKEN");
            return false;
        }

        // 鉴权关闭（本地调试）：允许旧的 X-User-Id 方式
        String headerUserId = request.getHeader("X-User-Id");
        Long fallbackUserId = 1L;
        if (headerUserId != null && !headerUserId.isBlank()) {
            try {
                fallbackUserId = Long.parseLong(headerUserId.trim());
            } catch (NumberFormatException e) {
                log.warn("X-User-Id 不是合法数字: {}，回退为用户 1", headerUserId);
            }
        }
        UserContext.setUserId(fallbackUserId);
        log.debug("鉴权已关闭，使用 X-User-Id 回退身份: userId={}", fallbackUserId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 必须清理：Tomcat 线程复用，漏清会让下一个请求继承上一个用户的身份
        UserContext.clear();
    }

    private void reject(HttpServletResponse response, String code, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"code\":401,\"message\":\"" + message + "\",\"data\":null}");
    }

    /**
     * 认证失败属于安全事件，落审计。
     */
    private void auditAuthFailure(HttpServletRequest request, String reason) {
        if (auditService == null) {
            return;
        }
        try {
            auditService.log(null, "AUTH_FAILED", request.getRequestURI(),
                    com.agi.assistant.model.enums.ToolRiskLevel.WARN, true, reason);
        } catch (Exception e) {
            log.debug("写入认证失败审计时出错: {}", e.getMessage());
        }
    }
}

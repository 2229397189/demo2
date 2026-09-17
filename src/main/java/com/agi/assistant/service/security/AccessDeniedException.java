package com.agi.assistant.service.security;

/**
 * 越权访问（HTTP 403）。
 * <p>
 * 用于「已认证，但访问的不是自己的资源」—— 例如请求
 * {@code /api/memory/{userId}} 时路径里的 userId 与当前登录用户不一致。
 * 与 {@link AuthenticationException} 区分开，是因为二者的处置方式不同：
 * 401 应引导重新登录，403 重试也没用（是权限边界问题，且属于需要审计的安全事件）。
 */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }
}

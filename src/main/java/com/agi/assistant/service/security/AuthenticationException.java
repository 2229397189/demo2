package com.agi.assistant.service.security;

/**
 * 认证失败（HTTP 401）。
 * <p>
 * 为什么需要专门的异常类型：项目里原有的做法是在控制器里
 * {@code return Result.fail(401, "...")}，但那只改了响应体里的 code，
 * HTTP 状态码仍然是 200 —— 网关、负载均衡、APM、前端统一拦截器
 * 都无法据此识别「这是未认证」，鉴权失败会被当成一次成功请求统计。
 * <p>
 * 配合 {@code GlobalExceptionHandler} 上的 {@code @ResponseStatus}，
 * 才能真正返回 HTTP 401。
 */
public class AuthenticationException extends RuntimeException {

    public AuthenticationException(String message) {
        super(message);
    }
}

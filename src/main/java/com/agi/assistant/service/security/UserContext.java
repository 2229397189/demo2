package com.agi.assistant.service.security;

/**
 * 当前请求的用户上下文。
 * <p>
 * 背景：项目里所有接口此前都用 {@code @RequestHeader(value="X-User-Id", defaultValue="1")}
 * 取用户身份 —— 这意味着任何人只要在请求头里写上 {@code X-User-Id: 2}，
 * 就能读写「用户 2」的全部会话、文档与记忆。<b>这不是认证，是可自定义的身份声明。</b>
 * <p>
 * 改造方向：身份只能来自服务端校验过的 JWT。校验结果放进本 ThreadLocal，
 * 业务代码从 {@link #getUserId()} 取值，不再直接信任请求头。
 * <p>
 * 关于 ThreadLocal 的三个要点（这也是它能被安全使用的条件）：
 * <ol>
 *   <li><b>必须在请求结束时清理</b>：Tomcat 复用线程，漏清会导致下一个请求
 *       继承上一个用户的身份（跨用户越权）。清理在拦截器的 afterCompletion 里做。</li>
 *   <li><b>不要在异步线程里直接读</b>：@Async / 线程池里的线程没有继承本值。
 *       需要在异步场景用身份时，必须在提交任务前把它取出来当参数传进去。</li>
 *   <li><b>不要用它传输大数据</b>：这里只放一个 userId。</li>
 * </ol>
 */
public final class UserContext {

    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();

    private UserContext() {
    }

    /**
     * 设置当前请求的用户 ID。
     */
    public static void setUserId(Long userId) {
        CURRENT_USER_ID.set(userId);
    }

    /**
     * 获取当前请求的用户 ID。
     *
     * @return 用户 ID；未认证时为 null
     */
    public static Long getUserId() {
        return CURRENT_USER_ID.get();
    }

    /**
     * 获取当前请求的用户 ID，未认证时抛出异常。
     * <p>
     * 适用于「走到这里必然已认证」的场景：与其拿到 null 后在下游某处
     * 产生一个莫名其妙的空指针，不如在边界上明确失败。
     *
     * @return 用户 ID
     * @throws IllegalStateException 未认证
     */
    public static Long requireUserId() {
        Long userId = CURRENT_USER_ID.get();
        if (userId == null) {
            throw new IllegalStateException("当前请求未经认证，无法获取用户身份");
        }
        return userId;
    }

    /**
     * 清理。必须在请求结束时调用，否则线程复用会造成身份串号。
     */
    public static void clear() {
        CURRENT_USER_ID.remove();
    }
}

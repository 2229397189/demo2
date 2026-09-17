package com.agi.assistant.service.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link UserContext} 测试。
 * <p>
 * 这里不是在测一个 ThreadLocal 能不能存取 —— 而是在测<b>身份不会串号</b>：
 * Tomcat 复用线程，只要有一个请求漏了 clear，下一个请求就会继承上一个用户的身份。
 */
class UserContextTest {

    @AfterEach
    void tearDown() {
        // 测试之间必须隔离，否则 ThreadLocal 残留会让后续用例产生假阳性
        UserContext.clear();
    }

    @Test
    @DisplayName("未认证时 getUserId 为 null、requireUserId 抛异常")
    void unauthenticatedState() {
        assertThat(UserContext.getUserId()).isNull();
        assertThatThrownBy(UserContext::requireUserId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未经认证");
    }

    @Test
    @DisplayName("设置后可读取，requireUserId 返回同一个值")
    void setAndRead() {
        UserContext.setUserId(42L);

        assertThat(UserContext.getUserId()).isEqualTo(42L);
        assertThat(UserContext.requireUserId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("clear 后身份彻底消失（防跨请求串号）")
    void clearRemovesIdentity() {
        UserContext.setUserId(7L);
        UserContext.clear();

        assertThat(UserContext.getUserId()).isNull();
        assertThatThrownBy(UserContext::requireUserId)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("同线程内可覆盖为另一个用户")
    void canBeOverwritten() {
        UserContext.setUserId(1L);
        UserContext.setUserId(2L);

        assertThat(UserContext.getUserId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("不同线程之间身份互相隔离")
    void identityIsIsolatedBetweenThreads() throws Exception {
        UserContext.setUserId(100L);

        final Long[] otherThreadValue = new Long[1];
        Thread worker = new Thread(() -> {
            // 子线程不应继承主线程的 ThreadLocal（这正是「异步里读不到身份」的原因）
            otherThreadValue[0] = UserContext.getUserId();
            UserContext.setUserId(200L);
        });
        worker.start();
        worker.join();

        assertThat(otherThreadValue[0]).as("子线程不应继承父线程身份").isNull();
        assertThat(UserContext.getUserId()).as("主线程身份不受子线程影响").isEqualTo(100L);
    }
}

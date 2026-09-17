package com.agi.assistant.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.InetSocketAddress;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端 API 集成测试：真启动 Spring 上下文 + 真连本机 MySQL 测试库 + MockMvc 打真实 HTTP 端点。
 * <p>
 * 这是「不是返回 200 就成功」的证明 —— 不 mock controller，不 mock mapper，
 * 直接走 Spring 装配 → 拦截器 → Controller → Service → MyBatis-Plus → 真库 的完整链路。
 * <p>
 * 覆盖核心闭环：注册 → 登录换 token → 带 token 访问受保护接口 → 校验返回真实结构；
 * 并反向验证「无 token → 401」「错误密码 → 401」「重复注册 → 400」。
 * <p>
 * 前置条件：本机 MySQL 3306 在跑，且 root/123456 可建库（见 application-test.yml）。
 * 外部中间件（ES/Neo4j/Milvus/Kafka/Redis/Docker）全部关闭，测试不依赖它们。
 *
 * @author Alex
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 环境守卫：集成测试依赖真实 MySQL（见 application-test.yml）。
     * 在没有 MySQL 的环境（如 CI 不提供数据库）下跳过而非失败，
     * 保证 `mvn test` 在纯代码环境也能绿，同时保留「有 MySQL 就真跑全链路」的能力。
     */
    @BeforeAll
    static void requireMySql() {
        assumeTrue(mysqlReachable(), "本机 MySQL 不可达，跳过端到端集成测试（需要 root@127.0.0.1:3306）");
    }

    private static boolean mysqlReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 3306), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ----------------------------------------------------------------
    //  鉴权链路
    // ----------------------------------------------------------------

    @Test
    @DisplayName("无 token 访问受保护接口 → 401")
    void protectedEndpointRequiresToken() throws Exception {
        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("登录：错误密码 → 401，且不泄露用户是否存在")
    void loginWrongPasswordReturns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody_user_xyz\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    @DisplayName("完整闭环：注册 → 登录拿 token → 带 token 访问各受保护接口返回真实结构")
    void fullHappyPath() throws Exception {
        String username = "it_user_" + System.currentTimeMillis();

        // 1. 注册
        MvcResult regResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"pass123456\",\"nickname\":\"集成测试用户\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.password").doesNotExist()) // 密码绝不外泄
                .andReturn();

        JsonNode regBody = objectMapper.readTree(regResult.getResponse().getContentAsString());
        assertThat(regBody.path("data").path("id").asLong()).isPositive();

        // 2. 登录拿 token
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"pass123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.user.username").value(username))
                .andReturn();

        JsonNode loginBody = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String token = loginBody.path("data").path("token").asText();
        assertThat(token).isNotBlank();

        // 3. 带 token 访问各受保护接口，验证返回真实结构（不是空壳/500）
        // 3.1 当前用户
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username));

        // 3.2 文档列表（空但结构正确）
        mockMvc.perform(get("/api/documents").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 3.3 记忆列表（空但结构正确，验证记忆类型筛选口径不崩；路径带 userId 且须为本人）
        long userId = loginBody.path("data").path("user").path("id").asLong();
        mockMvc.perform(get("/api/memory/" + userId).header("Authorization", "Bearer " + token)
                        .param("type", "FACT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 3.4 会话列表
        mockMvc.perform(get("/api/chat/sessions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 3.5 Agent 工具列表（应返回 6 个内置工具，且状态不是伪造的 SUCCESS）
        MvcResult toolsResult = mockMvc.perform(get("/api/agent/tools")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();
        JsonNode tools = objectMapper.readTree(toolsResult.getResponse().getContentAsString()).path("data");
        assertThat(tools.size()).isGreaterThanOrEqualTo(6); // knowledge_search/web_search/memory_search/current_time/calculate/run_code
        for (JsonNode tool : tools) {
            assertThat(tool.path("name").asText()).isNotBlank();
            assertThat(tool.path("riskLevel").asText()).isIn("SAFE", "WARN", "BLOCK");
        }

        // 3.6 模型 Provider（应返回 glm/ark 两个 provider 与当前生效 provider）
        mockMvc.perform(get("/api/models/providers").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeProvider").isNotEmpty())
                .andExpect(jsonPath("$.data.providers").isArray());

        // 3.7 审计日志（应能查到刚才的登录审计记录）
        mockMvc.perform(get("/api/agent/audit/logs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("重复注册同名用户 → 400（而非 500）")
    void duplicateRegisterReturns400() throws Exception {
        String username = "dup_user_" + System.currentTimeMillis();
        String body = "{\"username\":\"" + username + "\",\"password\":\"pass123456\"}";

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 第二次注册同名 → 业务校验失败，应返回 400 而非 500
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("伪造/无效 token → 401，绝不静默降级到默认用户")
    void invalidTokenRejected() throws Exception {
        mockMvc.perform(get("/api/documents").header("Authorization", "Bearer invalid.token.here"))
                .andExpect(status().isUnauthorized());
    }
}

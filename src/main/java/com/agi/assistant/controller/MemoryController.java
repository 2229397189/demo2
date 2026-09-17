package com.agi.assistant.controller;

import com.agi.assistant.model.dto.MemorySearchRequest;
import com.agi.assistant.model.dto.UserProfileDTO;
import com.agi.assistant.model.entity.Memory;
import com.agi.assistant.model.vo.Result;
import com.agi.assistant.service.MemoryService;
import com.agi.assistant.service.security.AccessDeniedException;
import com.agi.assistant.service.security.AuthenticationException;
import com.agi.assistant.service.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 记忆系统接口。
 * <p>
 * 权限修复说明：本控制器的路径里带 {@code userId}（如 {@code /api/memory/{userId}}），
 * 而身份校验此前完全不看这个值 —— 也就是说把 URL 里的数字一改，就能读到<b>任意用户</b>
 * 的记忆与画像。这是典型的 IDOR（越权访问资源）。
 * <p>
 * 现在的规则：接口保留原路径（不破坏既有调用方），但会强制校验
 * 「路径里的 userId == 当前登录用户」。不一致直接 403，而不是静默忽略参数 ——
 * 静默忽略会让调用方以为自己在读别人的数据，行为与预期不符更难排查。
 */
@Slf4j
@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
@Tag(name = "Memory", description = "记忆系统接口")
public class MemoryController {

    private final MemoryService memoryService;

    @GetMapping("/{userId}")
    @Operation(summary = "获取用户记忆", description = "获取指定用户的记忆列表，支持按类型过滤；仅允许访问当前登录用户")
    public Result<List<Memory>> getUserMemories(
            @Parameter(description = "用户ID") @PathVariable("userId") Long userId,
            @Parameter(description = "记忆类型") @RequestParam(required = false) String type,
            @Parameter(description = "返回数量限制") @RequestParam(defaultValue = "20") int limit) {

        checkOwnership(userId);

        int safeLimit = Math.max(1, Math.min(limit, 200));
        log.info("Get memories for user {}, type {}, limit {}", userId, type, safeLimit);
        return Result.ok(memoryService.getUserMemories(userId, type, safeLimit));
    }

    @PostMapping("/search")
    @Operation(summary = "搜索记忆", description = "根据查询内容搜索相关记忆；请求体中的 userId 会被覆盖为当前登录用户")
    public Result<List<Memory>> searchMemories(@Valid @RequestBody MemorySearchRequest request) {
        Long currentUserId = UserContext.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationException("未登录");
        }

        // 覆盖而不是拒绝：请求体里的 userId 是不可信输入，直接以认证身份为准
        request.setUserId(currentUserId);

        log.info("Search memories for user {}, query {}", currentUserId, request.getQuery());
        return Result.ok(memoryService.searchMemories(request));
    }

    @GetMapping("/profile/{userId}")
    @Operation(summary = "获取用户画像", description = "获取指定用户的画像信息；仅允许访问当前登录用户")
    public Result<UserProfileDTO> getUserProfile(
            @Parameter(description = "用户ID") @PathVariable("userId") Long userId) {

        checkOwnership(userId);

        log.info("Get profile for user {}", userId);
        return Result.ok(memoryService.getUserProfile(userId));
    }

    /**
     * 校验路径里的 userId 是否就是当前登录用户。
     * <p>
     * 不一致时抛 {@link AccessDeniedException} → HTTP 403（而不是返回 Result.fail，
     * 那样 HTTP 状态码仍是 200，越权在监控侧不可见）。
     *
     * @throws AuthenticationException 未登录
     * @throws AccessDeniedException   访问的不是自己的数据
     */
    private void checkOwnership(Long pathUserId) {
        Long currentUserId = UserContext.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationException("未登录");
        }
        if (pathUserId == null || !currentUserId.equals(pathUserId)) {
            log.warn("越权访问被拒: 当前用户={}, 请求访问用户={}", currentUserId, pathUserId);
            throw new AccessDeniedException("无权访问其他用户的数据");
        }
    }
}

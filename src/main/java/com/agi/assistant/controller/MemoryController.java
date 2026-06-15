package com.agi.assistant.controller;

import com.agi.assistant.model.dto.MemorySearchRequest;
import com.agi.assistant.model.dto.UserProfileDTO;
import com.agi.assistant.model.entity.Memory;
import com.agi.assistant.model.vo.Result;
import com.agi.assistant.service.MemoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
@Tag(name = "Memory", description = "记忆系统接口")
public class MemoryController {

    private final MemoryService memoryService;

    @GetMapping("/{userId}")
    @Operation(summary = "获取用户记忆", description = "获取指定用户的记忆列表，支持按类型过滤")
    public Result<List<Memory>> getUserMemories(
            @Parameter(description = "用户ID") @PathVariable("userId") Long userId,
            @Parameter(description = "记忆类型") @RequestParam(required = false) String type,
            @Parameter(description = "返回数量限制") @RequestParam(defaultValue = "20") int limit) {
        log.info("Get memories for user {}, type {}, limit {}", userId, type, limit);
        List<Memory> memories = memoryService.getUserMemories(userId, type, limit);
        return Result.ok(memories);
    }

    @PostMapping("/search")
    @Operation(summary = "搜索记忆", description = "根据查询内容搜索相关记忆")
    public Result<List<Memory>> searchMemories(@Valid @RequestBody MemorySearchRequest request) {
        log.info("Search memories for user {}, query {}", request.getUserId(), request.getQuery());
        List<Memory> memories = memoryService.searchMemories(request);
        return Result.ok(memories);
    }

    @GetMapping("/profile/{userId}")
    @Operation(summary = "获取用户画像", description = "获取指定用户的画像信息")
    public Result<UserProfileDTO> getUserProfile(
            @Parameter(description = "用户ID") @PathVariable("userId") Long userId) {
        log.info("Get profile for user {}", userId);
        UserProfileDTO profile = memoryService.getUserProfile(userId);
        return Result.ok(profile);
    }
}

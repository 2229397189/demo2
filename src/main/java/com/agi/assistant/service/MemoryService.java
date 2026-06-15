package com.agi.assistant.service;

import com.agi.assistant.model.dto.MemorySearchRequest;
import com.agi.assistant.model.dto.UserProfileDTO;
import com.agi.assistant.model.entity.Memory;

import java.util.List;

public interface MemoryService {

    /**
     * 获取用户记忆列表
     * @param userId 用户ID
     * @param type 记忆类型过滤（可选）
     * @param limit 返回数量限制
     */
    List<Memory> getUserMemories(Long userId, String type, int limit);

    /**
     * 搜索记忆
     */
    List<Memory> searchMemories(MemorySearchRequest request);

    /**
     * 获取用户画像
     */
    UserProfileDTO getUserProfile(Long userId);
}

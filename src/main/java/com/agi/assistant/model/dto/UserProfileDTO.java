package com.agi.assistant.model.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class UserProfileDTO {

    private Long userId;

    private String nickname;

    private List<String> topics;

    /**
     * 知识水平映射，key为领域，value为水平等级
     */
    private Map<String, String> knowledgeLevel;

    /**
     * 用户偏好设置
     */
    private Map<String, Object> preferences;

    /**
     * 总记忆数
     */
    private int totalMemories;

    /**
     * 平均重要性评分
     */
    private double averageImportance;

    /**
     * 高频访问的记忆内容
     */
    private List<String> topAccessedMemories;

    /**
     * 按类型分组的记忆内容
     */
    private Map<String, List<String>> memoriesByType;
}

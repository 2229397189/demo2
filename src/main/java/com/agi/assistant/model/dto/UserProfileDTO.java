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
}

-- AGI Assistant 数据库初始化脚本

CREATE DATABASE IF NOT EXISTS agi_assistant DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE agi_assistant;

-- 用户表
CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `username` VARCHAR(64) NOT NULL UNIQUE,
    `password` VARCHAR(255) NOT NULL,
    `nickname` VARCHAR(64),
    `email` VARCHAR(128),
    `avatar` VARCHAR(512),
    `status` TINYINT DEFAULT 1 COMMENT '0-禁用 1-正常',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_username` (`username`),
    INDEX `idx_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 会话表
CREATE TABLE IF NOT EXISTS `chat_session` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `title` VARCHAR(255),
    `retrieval_strategy` VARCHAR(32) DEFAULT 'hybrid' COMMENT 'dense/sparse/graph/hybrid',
    `status` TINYINT DEFAULT 1 COMMENT '0-归档 1-活跃',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话表';

-- 消息表
CREATE TABLE IF NOT EXISTS `chat_message` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `session_id` BIGINT NOT NULL,
    `role` VARCHAR(16) NOT NULL COMMENT 'user/assistant/system',
    `content` TEXT NOT NULL,
    `token_count` INT DEFAULT 0,
    `metadata` JSON COMMENT '扩展元数据',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_session_id` (`session_id`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息表';

-- 文档表
CREATE TABLE IF NOT EXISTS `document` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `title` VARCHAR(255) NOT NULL,
    `file_path` VARCHAR(512),
    `file_type` VARCHAR(32) COMMENT 'markdown/pdf/txt/html',
    `file_size` BIGINT DEFAULT 0,
    `chunk_count` INT DEFAULT 0,
    `status` TINYINT DEFAULT 0 COMMENT '0-待处理 1-处理中 2-已完成 3-失败',
    `tags` VARCHAR(512),
    `source` VARCHAR(255) COMMENT '来源URL或路径',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档表';

-- 文档分块表
CREATE TABLE IF NOT EXISTS `document_chunk` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `document_id` BIGINT NOT NULL,
    `chunk_index` INT NOT NULL,
    `content` TEXT NOT NULL,
    `token_count` INT DEFAULT 0,
    `metadata` JSON COMMENT '标题、标签等元数据',
    `vector_id` VARCHAR(64) COMMENT 'Milvus中的向量ID',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_document_id` (`document_id`),
    INDEX `idx_vector_id` (`vector_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档分块表';

-- 评测任务表
CREATE TABLE IF NOT EXISTS `evaluation_task` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `name` VARCHAR(255) NOT NULL,
    `dataset_id` VARCHAR(64) NOT NULL COMMENT '数据集标识',
    `retrieval_strategy` VARCHAR(32) DEFAULT 'hybrid',
    `model_id` VARCHAR(64) COMMENT '模型标识',
    `status` TINYINT DEFAULT 0 COMMENT '0-待执行 1-执行中 2-已完成 3-失败',
    `total_queries` INT DEFAULT 0,
    `completed_queries` INT DEFAULT 0,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评测任务表';

-- 评测结果表
CREATE TABLE IF NOT EXISTS `evaluation_result` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `task_id` BIGINT NOT NULL,
    `query_id` VARCHAR(64) NOT NULL,
    `query` TEXT NOT NULL,
    `generated_answer` TEXT,
    `expected_answer` TEXT,
    `retrieved_doc_ids` JSON,
    `retrieval_metrics` JSON COMMENT 'Recall@K, MRR, NDCG等',
    `generation_metrics` JSON COMMENT 'RAGAS指标',
    `latency_ms` BIGINT DEFAULT 0,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评测结果表';

-- 记忆表
CREATE TABLE IF NOT EXISTS `memory` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `content` TEXT NOT NULL,
    `type` VARCHAR(32) NOT NULL COMMENT 'short_term/long_term/graph/runtime',
    `importance` DOUBLE DEFAULT 0.5 COMMENT '重要性评分0-1',
    `access_count` INT DEFAULT 0,
    `last_accessed_at` DATETIME,
    `embedding_id` VARCHAR(64) COMMENT '向量ID',
    `metadata` JSON,
    `expires_at` DATETIME COMMENT '过期时间',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_type` (`type`),
    INDEX `idx_importance` (`importance`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='记忆表';

-- 审计日志表
CREATE TABLE IF NOT EXISTS `audit_log` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `user_id` BIGINT,
    `action` VARCHAR(64) NOT NULL,
    `resource` VARCHAR(128),
    `risk_level` VARCHAR(16) COMMENT 'SAFE/WARN/BLOCK',
    `blocked` TINYINT DEFAULT 0,
    `details` TEXT,
    `ip_address` VARCHAR(45),
    `user_agent` VARCHAR(512),
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_action` (`action`),
    INDEX `idx_risk_level` (`risk_level`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计日志表';

-- Golden Queries 测试集
CREATE TABLE IF NOT EXISTS `golden_query` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `dataset_id` VARCHAR(64) NOT NULL,
    `query` TEXT NOT NULL,
    `expected_answer` TEXT NOT NULL,
    `relevant_doc_ids` JSON COMMENT '相关文档ID列表',
    `difficulty` VARCHAR(16) DEFAULT 'medium' COMMENT 'easy/medium/hard',
    `category` VARCHAR(64),
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_dataset_id` (`dataset_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Golden Queries测试集';

-- 插入默认用户
INSERT INTO `user` (`username`, `password`, `nickname`, `email`) VALUES
('admin', '$2a$10$N.ZOn9G6w3Fz4nFHRXn5GOe9Th2jKZqK7TAKpXv4pG1wFkBmvUYCi', '管理员', 'admin@agi-assistant.com')
ON DUPLICATE KEY UPDATE `username` = `username`;

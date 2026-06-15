# AGI Assistant 智能体助手

面向程序员的 AI 学习助手，基于 **Spring Boot 3 + Vue 3** 前后端分离架构，帮助用户完成技术学习、知识沉淀与个人成长闭环。

## 技术栈

### 后端
- **框架**: Spring Boot 3.2.x + Java 17
- **构建工具**: Maven
- **数据库**: MySQL 8.0
- **缓存**: Redis 7.x
- **消息队列**: Kafka 3.x
- **向量数据库**: Milvus 2.4.x
- **全文检索**: Elasticsearch 8.x
- **图数据库**: Neo4j 5.x
- **ORM**: MyBatis-Plus

### 前端
- **框架**: Vue 3 + TypeScript
- **构建工具**: Vite 5
- **UI框架**: Element Plus
- **状态管理**: Pinia
- **路由**: Vue Router 4
- **图表**: ECharts
- **代码编辑器**: Monaco Editor

## 项目结构

```
demo2/
├── docker-compose.yml          # 基础设施部署
├── sql/                        # 数据库初始化脚本
├── src/                        # 后端代码
│   ├── main/java/com/agi/assistant/
│   │   ├── config/             # 配置类
│   │   ├── controller/         # REST控制器
│   │   ├── service/            # 业务服务
│   │   │   ├── rag/            # RAG检索服务
│   │   │   ├── memory/         # 记忆系统
│   │   │   ├── agent/          # Agent引擎
│   │   │   ├── harness/        # 容错执行引擎
│   │   │   ├── security/       # 安全沙箱
│   │   │   ├── evaluation/     # 评测系统
│   │   │   └── chat/           # 聊天服务
│   │   ├── model/              # 数据模型
│   │   ├── mapper/             # MyBatis-Plus Mapper
│   │   └── utils/              # 工具类
│   └── main/resources/
│       ├── application.yml     # 应用配置
│       └── prompts/            # 提示词模板
├── frontend/                   # 前端代码
│   ├── src/
│   │   ├── views/              # 页面组件
│   │   ├── components/         # 通用组件
│   │   ├── stores/             # Pinia状态
│   │   ├── api/                # API接口
│   │   └── types/              # TypeScript类型
│   └── package.json
└── .env.example                # 环境变量模板
```

## 功能模块

### 1. 混合检索 RAG 架构
- **三路召回**: Dense (Milvus向量检索) + Sparse (ES BM25) + Graph (Neo4j知识图谱)
- **融合排序**: RRF (Reciprocal Rank Fusion) 算法
- **流式生成**: SSE 流式返回 LLM 回答

### 2. RAG 全链路评测
- **Retrieval指标**: Recall@K, MRR, NDCG@K, HitRate, Precision@K
- **Generation指标**: RAGAS框架 (Faithfulness, Answer Relevancy, Context Precision/Recall)
- **评测管理**: 创建/编辑评测任务，对比结果展示

### 3. 自研记忆系统
- **四层架构**: 短期会话记忆 + 长期语义记忆 + 图记忆 + 运行时状态
- **记忆管理**: Fact Extraction, Hash去重, Embedding相似度去重, Graph-Aware Consolidation
- **生命周期**: TTL过期 + Importance动态衰减

### 4. 动态图 ReAct Runtime
- **DAG任务图**: 拓扑排序调度，并行执行无依赖节点
- **竞速策略**: 多搜索源/模型/检索策略竞速
- **ReAct循环**: Thought → Action → Observation 多轮推理

### 5. Harness 容错执行引擎
- **超时控制**: LLM/Tool/RAG/MCP 分级超时
- **重试策略**: 指数退避重试
- **软失败恢复**: Fallback策略 + 降级响应

### 6. 多层安全校验与沙箱
- **Docker沙箱**: 网络隔离、只读文件系统、资源限制
- **输入校验**: SQL注入/XSS/命令注入/路径遍历检测
- **审计日志**: Kafka异步记录操作审计

## 快速开始

### 1. 启动基础设施

```bash
# 启动所有中间服务
docker-compose up -d

# 等待所有服务就绪 (约2-3分钟)
docker-compose ps
```

### 2. 初始化数据库

```bash
# MySQL会自动执行 sql/init.sql 初始化表结构
# 如需手动执行:
mysql -h localhost -u root -proot123456 < sql/init.sql
```

### 3. 配置环境变量

```bash
# 复制环境变量模板
cp .env.example .env

# 编辑 .env 文件，配置以下变量:
# MYSQL_PASSWORD=your_mysql_password
# REDIS_PASSWORD=your_redis_password
# NEO4J_PASSWORD=your_neo4j_password
# XIAOMI_API_BASE_URL=https://api.xiaomi.com/v1
# XIAOMI_API_KEY=your_api_key
```

### 4. 启动后端

```bash
# 安装依赖并启动
./mvnw spring-boot:run

# 或使用IDE直接运行 AgiAssistantApplication.java
```

后端启动后访问: http://localhost:8080
API文档: http://localhost:8080/swagger-ui.html

### 5. 启动前端

```bash
cd frontend

# 安装依赖
npm install

# 启动开发服务器
npm run dev
```

前端访问: http://localhost:5173

## API接口

### 对话接口
```bash
# 流式对话 (SSE)
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 1" \
  -d '{"message": "什么是RAG？", "retrievalStrategy": "hybrid"}'
```

### 文档管理
```bash
# 上传文档
curl -X POST http://localhost:8080/api/documents/upload \
  -H "X-User-Id: 1" \
  -F "file=@document.md" \
  -F "title=RAG技术文档"

# 文档列表
curl http://localhost:8080/api/documents?page=1&size=20 \
  -H "X-User-Id: 1"
```

### 记忆系统
```bash
# 搜索记忆
curl -X POST http://localhost:8080/api/memory/search \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 1" \
  -d '{"userId": 1, "query": "RAG检索", "topK": 10}'

# 获取用户画像
curl http://localhost:8080/api/memory/profile/1
```

### 评测系统
```bash
# 创建评测任务
curl -X POST http://localhost:8080/api/evaluation/tasks \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 1" \
  -d '{"name": "Hybrid评测", "datasetId": "golden_queries_v1", "retrievalStrategy": "hybrid"}'
```

### 代码沙箱
```bash
# 执行代码
curl -X POST http://localhost:8080/api/sandbox/execute \
  -H "Content-Type: application/json" \
  -d '{"language": "python", "code": "print(\"Hello World\")", "timeout": 30}'
```

## 服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| Spring Boot | 8080 | 后端API |
| Vue Dev Server | 5173 | 前端开发 |
| MySQL | 3306 | 数据库 |
| Redis | 6379 | 缓存 |
| Kafka | 9092 | 消息队列 |
| Elasticsearch | 9200 | 全文检索 |
| Neo4j | 7474/7687 | 图数据库 |
| Milvus | 19530 | 向量数据库 |
| Milvus Console | 9001 | MinIO控制台 |

## 开发说明

### 添加新的检索策略
1. 在 `RetrievalStrategy` 枚举中添加新策略
2. 实现对应的检索服务类
3. 在 `HybridRetrievalService` 中注册新策略

### 添加新的工具
1. 在 `ToolRegistry` 中注册工具
2. 实现工具处理逻辑
3. 在 `ToolRiskClassifier` 中配置风险等级

### 自定义提示词
编辑 `src/main/resources/prompts/system-prompt.md` 自定义系统提示词

## License

MIT License

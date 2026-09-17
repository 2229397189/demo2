# AGI Assistant 云服务器部署清单

> 面向刚接触服务器部署的同学，一步一步照着做即可。
> 每一步都写清「在干什么、为什么、怎么验证」。
> 本文所有端口 / 键名均以仓库里的 **`docker-compose.yml`**、**`src/main/resources/application.yml`**、**`.env.example`** 实际值为准（已逐个核对）。
>
> 相关文件：`docker-compose.yml`、`.env.example`、`scripts/smoke.sh`、`src/main/resources/application.yml`
> 启动能力矩阵由 `config/StartupCapabilityLogger.java` 在应用启动完成后打印。

---

## 0. 前置条件

| 需要 | 说明 |
|------|------|
| JDK 17 | 后端是 Spring Boot 3 + Java 17。`java -version` 应显示 17.x |
| Maven 3.9+ | 只有构建阶段需要（服务器上可以用打包好的 jar，不必装 Maven） |
| Docker + Docker Compose | 用来起 Redis / Kafka / ES / Neo4j / Milvus 等中间件 |
| MySQL 8 | **`docker-compose.yml` 里没有 MySQL 服务**（注释说明「已有本地 MySQL 运行在 3306，跳过容器化」）。所以服务器上需要**自己安装并把 MySQL 跑在 3306** |
| 内存 | 中间件全家桶较重，建议 **≥ 8GB**（ES 单节点固定 512MB 堆、Milvus+etcd+MinIO 另算） |

**构建产物**（在项目根目录执行）：

```bash
mvn clean package            # 生成 target/agi-assistant-1.0.0-SNAPSHOT.jar
```

> ⚠️ **jar 必须在「项目根目录」下启动**。原因见第 2 步：`.env` 与 `sql/init.sql` 都用了**相对路径**，
> 脱离项目根目录启动会读不到 `.env`、也执行不了建表脚本。

---

## 1. 架构与端口一览

| 组件 | 容器内端口 | 宿主机端口 | 是否有开关 | 说明 |
|------|-----------|-----------|-----------|------|
| **应用（Spring Boot）** | — | **8080** | — | `server.port: 8080` |
| **MySQL** | — | **3306** | — | 需自行安装；compose 未包含 |
| **Redis** | 6379 | **6379** | — | 缓存 |
| **Zookeeper** | 2181 | **2181** | — | Kafka 依赖 |
| **Kafka** | 9092 | **9092** | `KAFKA_ENABLED` | 审计旁路 |
| **Elasticsearch** | 9200 | **9201** | 无（依赖连通性） | ⚠️ 容器 9200 映射到宿主机 **9201**；`application.yml` 的 `spring.elasticsearch.uris` 也指向 `localhost:9201`，**两者一致**。宿主机上请连 **9201**，不是 9200 |
| **Elasticsearch transport** | 9300 | **9300** | — | 集群内部通信 |
| **Neo4j（HTTP）** | 7474 | **7474** | — | 浏览器管理台 |
| **Neo4j（Bolt）** | 7687 | **7687** | `NEO4J_ENABLED` | 图数据库 |
| **Milvus（gRPC）** | 19530 | **19530** | `MILVUS_ENABLED` | 向量库 |
| **Milvus（健康/指标）** | 9091 | **9091** | — | `http://localhost:9091/healthz` |
| **Milvus MinIO（API）** | 9000 | **9000** | — | Milvus 对象存储 |
| **MinIO Console** | 9001 | **9001** | — | 管理台 |
| **Milvus etcd** | 2379 | 未映射 | — | 仅容器网络内使用 |

> 端口对应关系来自 `docker-compose.yml` 的 `ports:` 段与 `application.yml`，已逐个核对。
> ⚠️ **README 的端口表有两处与代码/compose 不符**（见第 6 章「已知文档偏差」）。

---

## 2. 第二步之外先看：`.env` 的加载机制与「死配置陷阱」（必读）

这是本项目**最容易踩、且不报错**的坑，务必先读：

1. `application.yml` 第 13 行是：
   ```yaml
   spring.config.import: optional:file:.env[.properties]
   ```
   含义：把 `.env` 当作 **properties 文件**读取，且是**相对路径** —— **必须放在进程的工作目录下**（即你在哪执行 `java -jar`，`.env` 就要在那个目录）。

2. **死配置陷阱**：Spring 的宽松绑定（`ARK_ENABLED` → `ark.enabled`）**只对真实的操作系统环境变量生效，对 properties 文件里的 `UPPER_SNAKE` 键不生效**。
   因此，`.env` 里的任何一个键，**如果 `application.yml` 中没有对应的 `${KEY:default}` 显式占位，它就是死配置 —— 填了也不生效，而且不报错**。

   👉 结论：改 `.env` 时，只放「`application.yml` 里确实写了 `${KEY:...}` 占位」的键。本仓库已验证的**死键**（填了没用）：
   - `NEO4J_MAX_CONNECTION_POOL_SIZE`、`NEO4J_CONNECTION_ACQUISITION_TIMEOUT`（`.env.example` 里有，但 `application.yml` 无占位）
   - 本地 `.env` 里的 `AMAP_KEY`（全仓库无任何引用）

   👉 想让一个「当前是死键」的配置真正生效，正确做法是**先去 `application.yml` 补 `${KEY:default}` 占位**，再在 `.env` 里填。

3. **配置键名不一致的坑（已修，但要知道正确键名）**：
   - 沙箱「执行前是否要求确认」的正确键是 **`app.sandbox.require-confirm`**（不是 `sandbox.require-confirm`）。
     历史 bug：`SandboxServiceImpl` 曾读 `${sandbox.require-confirm}`，而 yml 里实际挂在 `app.sandbox.require-confirm` 下 → 安全门槛永远读不到 `true`。现读 `${app.sandbox.require-confirm:${sandbox.require-confirm:false}}`（向后兼容）。
   - 线程池配置键是 **`harness.pool.*`**（不是 `harness.tool.pool.*`）；工具超时键是 **`harness.timeout.tool-timeout`**（默认 10000ms）。

---

## 3. 第一步：启动中间件

在**项目根目录**执行：

```bash
# 启动全部中间件
docker compose up -d

# 查看容器状态（等待 ES / Milvus 变 healthy，约 2~3 分钟）
docker compose ps
```

> 注：`docker compose ps` 里 **不会出现 MySQL**（compose 未包含），请自行保证 3306 上有 MySQL。

### 逐个验证「都活着」

```bash
# Redis
docker exec agi-redis redis-cli -a "${REDIS_PASSWORD:-redis123456}" ping            # 返回 PONG

# Kafka（列出 topic，能连上即正常）
docker exec agi-kafka kafka-topics --bootstrap-server localhost:9092 --list

# Elasticsearch —— 注意宿主机端口是 9201
curl -s http://localhost:9201/_cluster/health                                # status 为 green / yellow 均可

# Neo4j（HTTP 管理台，返回 200 即进程已起；登录密码见 .env）
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:7474               # 期望 200

# Milvus（健康检查）
curl -s http://localhost:9091/healthz                                        # 期望 OK

# Milvus MinIO Console
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:9001               # 期望 200/307

# MySQL（本机 3306）
mysql -h 127.0.0.1 -P 3306 -u root -p -e 'SELECT VERSION();'
```

> 如果某个中间件起了但应用连不上，先看第 6 章「常见故障排查表」。

---

## 4. 第二步：配置 `.env`

`.env` **必须放在项目根目录（= 你执行 `java -jar` 的工作目录）**。下面是一份**服务器版完整模板**，`# 服务器：` 注释标出与本地不同的项：

```dotenv
# ============================================================
#  AGI Assistant 服务器版 .env（放在 jar 的工作目录下）
#  注意：UPPER_SNAKE 键只有 application.yml 里有 ${KEY:...} 占位才生效（见第 2 步）
# ============================================================

# ---------- MySQL ----------
DB_HOST=localhost
DB_PORT=3306
DB_NAME=agi_assistant
DB_USERNAME=root
DB_PASSWORD=ChangeMe_StrongPassword           # 服务器：换成强密码（不要用默认 123456）

# ---------- Redis ----------
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=redis123456                     # 服务器：与 docker-compose 里 Redis 实际密码一致（compose 默认 redis123456）

# ---------- Kafka（审计异步通道）----------
KAFKA_ENABLED=true                             # 服务器：true（本地为 false）。不开则审计降级为 DB + 本地日志
KAFKA_BOOTSTRAP_SERVERS=localhost:9092

# ---------- Milvus（向量检索）----------
MILVUS_ENABLED=true                            # 服务器：true（本地为 false）。不开则 Dense 召回跳过，Hybrid 退化为 ES 单路
MILVUS_HOST=localhost
MILVUS_PORT=19530
MILVUS_DATABASE=default

# ---------- Elasticsearch（BM25 稀疏检索）----------
ELASTICSEARCH_URIS=localhost:9201              # 注意宿主机端口是 9201（容器 9200）
ELASTICSEARCH_USERNAME=
ELASTICSEARCH_PASSWORD=

# ---------- Neo4j（知识图谱 / 图记忆）----------
NEO4J_ENABLED=true                             # 服务器：true（本地为 false）。不开则图谱检索恒空
NEO4J_URI=bolt://localhost:7687
NEO4J_USERNAME=neo4j
NEO4J_PASSWORD=neo4j123456                     # 服务器：与 docker-compose 的 NEO4J_PASSWORD 一致

# ---------- RAG 基础设施初始化 ----------
RAG_INITIALIZER_ENABLED=true                   # 服务器：必须 true，否则 Milvus collection 与 ES index mapping 永远不会被创建
ES_INDEX_ANALYZER=standard                     # 装了 analysis-ik 插件后可改 ik_max_word
ES_SEARCH_ANALYZER=standard                    # 装了 analysis-ik 插件后可改 ik_smart

# ---------- 知识图谱构建 ----------
RAG_GRAPH_EXTRACTION_ENABLED=true              # 文档入库时做实体抽取（会消耗 LLM 调用）
RAG_GRAPH_EXTRACTION_MAX_CHUNKS=40

# ---------- LLM（智谱 GLM，OpenAI 兼容）----------
OPENAI_BASE_URL=https://open.bigmodel.cn/api/paas/v4
OPENAI_API_KEY=填写智谱APIKey（格式 id.secret）
OPENAI_MODEL=glm-4.5-air
OPENAI_MAX_TOKENS=4096
OPENAI_TEMPERATURE=0.7
OPENAI_TIMEOUT=120
OPENAI_CONNECT_TIMEOUT=30
OPENAI_THINKING=disabled                       # 关闭思维链，避免简单问答把 token 烧在 reasoning 上

# ---------- Embedding（阿里云百炼 DashScope）----------
EMBEDDING_PROVIDER=auto                        # remote=仅远程 / local=仅本地哈希降级 / auto=远程失败自动降级
EMBEDDING_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
EMBEDDING_API_KEY=填写DashScope APIKey（sk- 开头）
EMBEDDING_MODEL=text-embedding-v3
EMBEDDING_DIMENSIONS=1024                      # 必须与 Milvus collection 维度一致，改动后需重建 collection

# ---------- 鉴权 ----------
JWT_SECRET=请替换为长随机串（≥32 字符，如 openssl rand -hex 32 的输出）   # 服务器：务必更换
JWT_EXPIRE_HOURS=168
AUTH_ENABLED=true                              # 服务器：true（本地为 false）。⚠️ 不改就是无鉴权裸奔

# ---------- 记忆生命周期 ----------
MEMORY_DECAY_ENABLED=true
MEMORY_DECAY_CRON=0 0 3 * * ?

# ---------- 对话行为 ----------
CHAT_AUTO_EXECUTE_CODE=false
CHAT_REACT_MIN_LENGTH=300

# ---------- Agent 规划 ----------
PLAN_USE_LLM=true

# ---------- 上传 ----------
UPLOAD_DIR=./uploads                           # 相对路径，同样依赖「在项目根目录启动」

# ---------- 沙箱（Docker）----------
DOCKER_HOST=unix:///var/run/docker.sock        # Linux 默认值（本机 Windows 会自动转 npipe）
SANDBOX_ENABLED=true
SANDBOX_REQUIRE_CONFIRM=true                   # 服务器：true（本地为 false）。对应正确键 app.sandbox.require-confirm
SANDBOX_NETWORK_DISABLED=true
SANDBOX_READONLY_FS=true
SANDBOX_TMPFS=256m
SANDBOX_PYTHON_IMAGE=python:3.11-slim
SANDBOX_NODE_IMAGE=node:20-slim
SANDBOX_JAVA_IMAGE=eclipse-temurin:17-jdk
SANDBOX_TIMEOUT=60
SANDBOX_MEMORY=512m
SANDBOX_CPU=1.0

# ---------- 模型 Provider 选择 & 方舟 Ark（可选）----------
LLM_PROVIDER=glm                               # glm / ark；非法值回退 glm
ARK_ENABLED=false                              # 没有方舟 key 就保持 false，代码会优雅降级
ARK_BASE_URL=https://ark.cn-beijing.volces.com/api/v3
ARK_API_KEY=
ARK_MODEL=
```

### 与本地不同的关键项（务必改）

| 键 | 本地值 | 服务器应改为 | 不改的后果 |
|----|-------|-------------|-----------|
| `AUTH_ENABLED` | false | **true** | 接口无鉴权，任何人可读写数据（**裸奔**） |
| `SANDBOX_REQUIRE_CONFIRM` | false | **true** | 沙箱可被无确认执行任意代码 |
| `MILVUS_ENABLED` | false | **true** | 混合检索退化为 ES 单路（无向量召回） |
| `NEO4J_ENABLED` | false | **true** | 图谱检索恒空 |
| `KAFKA_ENABLED` | false | **true** | 审计只剩 DB + 本地日志 |
| `RAG_INITIALIZER_ENABLED` | true | true | 若为 false，Milvus collection 与 ES index mapping 永不创建 |
| `JWT_SECRET` | 固定串 | **长随机串** | 令牌可被伪造 |
| `DB_PASSWORD` | 123456 | **强密码** | 数据库弱口令 |

---

## 5. 第三步：启动应用 + 冒烟自检

```bash
# 在项目根目录（.env 所在目录）启动
nohup java -jar target/agi-assistant-1.0.0-SNAPSHOT.jar > app.log 2>&1 &

# 看启动日志（找到 Started 行 + 能力矩阵）
grep -E 'Started AgiAssistantApplication|\[CAPABILITY\]' app.log
```

启动完成后，日志里会打印一张**能力矩阵**（由 `StartupCapabilityLogger` 输出，每行以 `[CAPABILITY]` 开头），一眼看清各可选依赖是否就绪：

```text
[CAPABILITY] AGI Assistant 启动能力矩阵 / STARTUP CAPABILITY MATRIX
[CAPABILITY] 组件             | 启用开关                            | 实际可用性
[CAPABILITY] LLM provider     | enabled=provider=glm               | available=true   | active=glm, glm=true, ark=false
[CAPABILITY] Embedding        | enabled=provider=auto              | available=true   | mode=auto, remoteKey=true（远程 embedding 已配置）
[CAPABILITY] Milvus           | enabled=milvus.enabled=true        | available=true   | client 已连接
[CAPABILITY] Elasticsearch    | enabled=n/a(无开关,依赖连通性)       | available=true   | uri=localhost:9201
[CAPABILITY] Neo4j            | enabled=neo4j.enabled=true         | available=true   | driver=已连接, graphExtraction=true
[CAPABILITY] Kafka            | enabled=spring.kafka.enabled=true  | available=true   | bootstrap=localhost:9092
[CAPABILITY] Docker Sandbox   | enabled=app.sandbox.enabled=true   | available=true   | dockerHost=unix:///var/run/docker.sock
[CAPABILITY] 所有可选组件（Milvus / Neo4j / Kafka）均已启用
[CAPABILITY] 已启用的组件探测结果均正常
```

若某项显示 `available=false`，末尾还会给出**汇总提示**，例如：
`[CAPABILITY] 未启用的可选组件：Milvus, Neo4j, Kafka —— 相关检索/审计能力将走降级路径`。

### 一键自检脚本

```bash
bash scripts/smoke.sh
```

脚本会依次：检查 `java` → 探测 MySQL 可达性 → 检查 8080 端口 → 按需 `nohup java -jar` 启动 → 用 `curl` 轮询探活 → 打印能力矩阵日志 → 给出后续验证提示。

> 探活打的是 **`/v3/api-docs`**（SpringDoc 文档），它**不在 `/api/**` 下**，因此**不受鉴权拦截**，即使 `AUTH_ENABLED=true` 也能 200。若改用 `/api/**` 下的接口，开启鉴权后会返回 401。

---

## 6. 第四步：验证关键能力

以下命令默认 `AUTH_ENABLED=true`（服务器配置）。

```bash
BASE=http://localhost:8080

# (1) 注册一个用户（用户名 3~64 位，密码 ≥8 位）
curl -s -X POST $BASE/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo12345","nickname":"Demo"}'

# (2) 登录拿 token（响应里 data.token）
TOKEN=$(curl -s -X POST $BASE/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo12345"}' \
  | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
echo "TOKEN=$TOKEN"

# (3) 看模型 provider 可用性（glm / ark）
curl -s $BASE/api/models/providers -H "Authorization: Bearer $TOKEN"

# (4) 上传一篇文档（返回的 id 记下来）
curl -s -X POST $BASE/api/documents/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@README.md" -F "title=部署验证"

# (5) 触发文档处理（解析 / 分块 / 向量化 / 索引）
curl -s -X POST $BASE/api/documents/1/process -H "Authorization: Bearer $TOKEN"

# (6) 流式对话（SSE，检索策略 hybrid / full）
curl -N -X POST $BASE/api/chat/stream \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"项目的备选模型 provider 有哪些？","retrievalStrategy":"hybrid"}'

# (7) 看审计（无 API 端点，直接查库）
mysql -h 127.0.0.1 -u root -p agi_assistant \
  -e "SELECT event_id, action, resource, risk_level, blocked, created_at FROM audit_log ORDER BY id DESC LIMIT 20;"
```

> 若 `AUTH_ENABLED=false`（本地调试），把 `-H "Authorization: Bearer $TOKEN"` 换成 `-H "X-User-Id: 1"` 即可。

---

## 7. 常见故障排查表

| # | 现象 | 原因 | 解法 |
|---|------|------|------|
| 1 | `.env` 里改了值但**完全不生效，也不报错** | 该键在 `application.yml` 里**没有 `${KEY:default}` 占位** → 死配置；或 `.env` 不在进程工作目录（`file:.env` 是相对路径） | 先确认 `application.yml` 有该键占位；确认 `java -jar` 在 `.env` 所在目录执行 |
| 2 | 打开了确认门槛开关但沙箱仍不要求确认 | 键名不一致：yml 挂在 `app.sandbox.require-confirm`，旧代码读 `sandbox.require-confirm` | 用正确键 **`app.sandbox.require-confirm`**（现版本已兼容旧键，但文档以新键为准） |
| 3 | 想调线程池 / 工具超时却没反应 | 键名是 `harness.pool.*`，不是 `harness.tool.pool.*`；工具超时是 `harness.timeout.tool-timeout` | 按正确键名配置 |
| 4 | ES 连不上 / 索引为空 | 端口混淆：容器 **9200** 映射到宿主机 **9201** | 宿主机连 **9201**：`ELASTICSEARCH_URIS=localhost:9201` |
| 5 | 前端起在 5173 但打不开 | `frontend/vite.config.ts` 端口实际是 **3000**（README 旧文档写 5173 有误） | 访问 `http://<host>:3000` |
| 6 | `Address already in use` | 8080 端口被占用 | `lsof -i:8080` 找到进程 kill，或用 `APP_PORT` 覆盖自检脚本 |
| 7 | 应用启动即退出 / 数据源初始化失败 | MySQL 未启动、密码错、或库字符集问题 | 核对 `DB_*`；建库用 `utf8mb4`：`CREATE DATABASE agi_assistant CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;` |
| 8 | Neo4j 报认证失败 | `.env` 的 `NEO4J_PASSWORD` 与 compose 的 `NEO4J_PASSWORD` 不一致（compose 只在首次初始化时写入密码） | 两侧改为一致；必要时清空 neo4j 数据卷重建 |
| 9 | 磁盘写满 / 容器异常重启 | ES / Milvus / MinIO 数据卷增长快 | `docker system df` 查看；清理无用镜像/卷，预留 ≥ 20GB |
| 10 | Redis 连接被拒 / NOAUTH | `.env` 的 `REDIS_PASSWORD` 与 Redis 实际密码不一致 | 与 compose 的 Redis 密码保持一致（compose 默认 `redis123456`） |
| 11 | 启动了却没打能力矩阵 | 应用没起来，或日志文件路径不对 | 看 `app.log` 尾部；确认 `Started AgiAssistantApplication` 出现 |
| 12 | 服务 401 | `AUTH_ENABLED=true` 且请求未带 token（**这是预期行为**） | 先登录拿 token，带 `Authorization: Bearer <token>` |

---

## 8. 诚信提醒（评测口径）

> **未配置真实 embedding / LLM key 时，评测指标会显示为「未评估（`null`）」而不是 `0`。这是有意设计，不要用假数据填充。**
>
> - RAGAS 生成类指标（Faithfulness / Answer Relevancy / Context Precision / Context Recall）在无真实 key 时不可计算，快照里输出 `null` 且 `evaluated=false`。
> - 均值聚合会**剔除未评估的哨兵值**，不会把 `null` 当成 0 拉低平均分。
> - 请如实区分「未评估」与「得分为 0」——前者是没测，后者是测了且很差，两者含义完全不同。

---

## 附：本文核对到的「文档与代码偏差」清单

部署/排障时若与其它文档冲突，以**代码与本文**为准：

1. **README 前端端口写 5173，实际 `frontend/vite.config.ts` 是 3000**（本次已修正 README）。
2. **README 端口表写 Elasticsearch `9200`，实际宿主机端口是 `9201`**（`docker-compose.yml` 把容器 9200 映射到宿主机 9201，`application.yml` 也指向 9201）。README 该行未在本次改动范围内，以此处为准。
3. **README 快速开始的 MySQL 命令用 `-proot123456`，而 `application.yml` 默认密码是 `123456`**，以 `application.yml` / `.env` 为准。
4. `.env.example` 中的 `NEO4J_MAX_CONNECTION_POOL_SIZE`、`NEO4J_CONNECTION_ACQUISITION_TIMEOUT` 为**死键**（无 yml 占位）。
5. 本地 `.env` 里的 `AMAP_KEY` 全仓库无引用（死键）。
6. `docker-compose.yml` 未包含 MySQL 服务（注释明确「跳过容器化」），服务器需自备 MySQL。

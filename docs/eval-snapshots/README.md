# 评测快照目录（真实运行产物）

本目录存放**由程序真实产出**的评测快照，用于自证「评测管线端到端可跑并产出结构化、可复现的产物」。

> **诚信声明**：本目录下的 `.json` / `.md` 快照全部由
> `com.agi.assistant.service.evaluation.EvaluationSnapshotService#exportSnapshot` 在**一次真实评测运行**中自动生成，
> 未做任何人工编辑、未替换任何一个数值。原始文件按生成时的字节原样提交。

---

## 1. 这份快照是什么

一次**真实评测运行**的产物。运行链路：

```
上传 4 篇 PDF → 文档解析/分块 → （索引：Milvus / ES / Neo4j 本机均未启动，故跳过）
→ POST /api/evaluation/datasets/build（用 GLM 为每篇文档生成 1 条 golden query，逐条落库）
→ 建评测任务 → run（@Async，逐条：检索 → 调用 GLM 生成答案 → RAGAS 生成质量评估）
→ POST /api/evaluation/tasks/{id}/snapshot（导出 json + md）
```

本目录内文件：

| 文件 | 说明 |
|------|------|
| `snapshot-real-2026-HYBRID-20260917-205951.json` | 第 1 次导出（本次运行的快照正本） |
| `snapshot-real-2026-HYBRID-20260917-205951.md` | 同上，人读 Markdown 版 |
| `snapshot-real-2026-HYBRID-20260917-205954.json` | 同一任务第 2 次导出（**用于「结构确定性」验证**，见第 4 节） |
| `snapshot-real-2026-HYBRID-20260917-205954.md` | 同上，人读 Markdown 版 |

> 保留两次导出的两个文件，是为了让任何人**可以自己去 diff** 复现第 4 节的结论，而不是只能相信一句"结构确定"的声称。

---

## 2. 产生它的环境（关键：诚实交代各组件开关状态）

运行机器为本地开发机，当时各组件实际状态如下（来自应用启动日志的能力矩阵）：

| 组件 | 开关 / 状态 | 实际可用性 | 对本次运行的影响 |
|------|-------------|-----------|------------------|
| **MySQL 3306** | 已启动 | 可用 | 任务/文档/golden query/结果全部落库 |
| **LLM provider（智谱 GLM）** | `LLM_PROVIDER=glm` | **可用**（active=glm） | 生成答案、生成 golden query、RAGAS 评估均**真实调用了 LLM** |
| **Embedding（DashScope）** | `EMBEDDING_PROVIDER=auto`，远程 key 已配 | 可用 | （本机 Milvus 未开，向量未被写入） |
| **Elasticsearch** | 无开关，依赖连通性 | **不可用**（`localhost:9201` Connection refused） | **BM25 稀疏检索返回空** |
| **Milvus** | `MILVUS_ENABLED=false` | 不可用 | **Dense 向量召回应被跳过** |
| **Neo4j** | `NEO4J_ENABLED=false` | 不可用 | **图谱检索恒空** |
| **Kafka** | `KAFKA_ENABLED=false` | 不可用 | 审计降级为 DB + 本地日志 |
| **Docker** | — | 不可用 | 沙箱不可用 |

### ⚠️ 必读：检索指标为 0 是「检索后端未启动」，不是「检索质量差」

本次快照中 **Recall@K / Precision@K / MRR / NDCG@K / HitRate 全部为 `0.0000`**。

原因：Elasticsearch / Milvus / Neo4j **三个检索后端当时都没有启动**。
应用侧 `HybridRetrievalService` 在每个 query 上都返回了 **0 条结果**（日志可见：
`Retrieval completed: strategy=HYBRID, results=0`，伴随 `BM25 search unavailable ... Connection refused`）。
检索结果为空 → 与 golden query 的 `relevantDocIds` 零命中 → 四指标与 HitRate 自然为 **0.0**。

> 换句话说：**这不是检索算法效果差，而是「根本没有可用的检索后端」**。
> 换任何一套检索算法，在后端全关的情况下都只能得到 0。请勿把此处的 0 解读为系统检索能力为零。

另需注意：快照里检索汇总的 `"evaluated": true` 表示**这些指标被真实计算过**（空结果集被评估器真实打分），
而不是「未评估」。`0.0` 是**真实测得的分数**（一个合法的 0 分），与「未评估」语义不同 —— 未评估在快照中输出 `null`。

**生成类指标则是真实有值的**（因为 GLM 真实可用）：Faithfulness=1.0、Answer Relevancy≈0.44、
Context Precision=0.0、Context Recall≈0.017。其中 Context Precision/Recall 偏低同样是因为
**送入 LLM 的上下文是空的**（检索没返回任何内容），而非评估器故障。

---

## 3. 产生它的完整命令（可复制粘贴复现）

前提：`.env` 放在**项目根目录**（`spring.config.import: optional:file:.env[.properties]` 是相对路径），
且 `AUTH_ENABLED=false`（本地；鉴权关闭时用 `X-User-Id` 头回退身份，默认用户 1）。
以下命令均在**项目根目录**执行。

```bash
# 0) 构建（不 clean）
mvn -q -DskipTests package

# 1) 启动（务必显式指定端口：本机沙箱会注入随机 SERVER_PORT）
java -jar target/agi-assistant-1.0.0-SNAPSHOT.jar --server.port=8080
#   启动成功后日志可搜到：Started AgiAssistantApplication ... 以及 [CAPABILITY] 能力矩阵

# 2) 上传 4 篇 PDF（uploads/ 下真实的 4 个文件）
for f in 4d5e154eef184af690b314aac2d51804.pdf \
         9b134f9376b1409b9f15879e01110e8c.pdf \
         ba83ed670824401b8244a07ccfe7aaba.pdf \
         bb3a1fe44d064ee7827ba013f332b87a.pdf ; do
  curl -s -X POST http://localhost:8080/api/documents/upload \
    -H "X-User-Id: 1" \
    -F "file=@uploads/$f" -F "title=$f" -F "source=uploads-local"
done
#   本次返回的文档 id：10 / 11 / 12 / 13（status=0 PENDING）

# 3) 触发处理（@Async）；轮询 GET /api/documents 直到 status 不再是 0/1
for id in 10 11 12 13 ; do
  curl -s -X POST "http://localhost:8080/api/documents/$id/process" -H "X-User-Id: 1"
done
curl -s "http://localhost:8080/api/documents?page=1&size=10" -H "X-User-Id: 1"
#   本次 4 篇最终均为 status=2（COMPLETED）

# 4) 用 LLM 从文档构建 benchmark 数据集（limit=10 覆盖已处理文档）
curl -s -X POST http://localhost:8080/api/evaluation/datasets/build \
  -H "X-User-Id: 1" -H "Content-Type: application/json" \
  -d '{"datasetId":"snapshot-real-2026","limit":10,"useLlm":true}'
#   本次返回：{"imported":6,...}（4 篇本次文档 + 2 篇历史 COMPLETED 文档）

# 5) 建评测任务，然后运行
curl -s -X POST http://localhost:8080/api/evaluation/tasks \
  -H "X-User-Id: 1" -H "Content-Type: application/json" \
  -d '{"name":"真实快照评测-run1","datasetId":"snapshot-real-2026","retrievalStrategy":"HYBRID","modelId":"glm-4.5-air"}'
#   本次返回任务 id=5
curl -s -X POST http://localhost:8080/api/evaluation/tasks/5/run -H "X-User-Id: 1"
#   轮询 GET /api/evaluation/tasks 直到 status 变为 2(COMPLETED)/3(FAILED)
#   本次状态流转：RUNNING(0→1→2→3→4→5 条完成) → COMPLETED(6/6)

# 6) 导出快照（json + md，写入 evaluation.snapshot.dir = docs/eval-snapshots）
curl -s -X POST http://localhost:8080/api/evaluation/tasks/5/snapshot -H "X-User-Id: 1"
#   返回：{"jsonPath":"docs/eval-snapshots/snapshot-real-2026-HYBRID-<ts>.json",
#          "mdPath":"docs/eval-snapshots/snapshot-real-2026-HYBRID-<ts>.md"}
```

本次运行的实际结果摘要：

- 新增文档：4 篇，最终状态 **全部 COMPLETED(status=2)**，chunkCount = 3 / 3 / 88 / 3。
  每篇的 `errorMessage` 如实记录了降级原因：`Milvus 未启用，未建立向量索引; Elasticsearch 不可用，未建立 BM25 索引; Neo4j 未启用，未构建知识图谱`。
  （注：这三项是「可选后端未启用」的**降级记录**，不计入失败，故状态为 COMPLETED 而非 PARTIAL/FAILED。没有扫描件解析失败的情况。）
- `datasets/build` 返回 **`imported = 6`**。
- 评测任务 **id=5**，状态由 PENDING(0) → RUNNING(1)（completed 0→1→2→3→4→5）→ **COMPLETED(2)，6/6**。

---

## 4. 结构确定性验证（在真实产物上）

`EvaluationSnapshotService` 声称：「同一个 task 导出两次，除 `meta.generatedAt` 外结构一致」。

**在本次真实产物上验证成立**。对同一任务（id=5）连续导出两次，得到
`...-205951.json` 与 `...-205954.json`，把 `generatedAt` 归一化后比对：

- 两个文件**字节数相同**（各 3714 字节）；
- 归一化 `generatedAt` 后，**逐行完全一致（差异行数 = 0）**；
- 唯一不同的原始行是第 6 行 `"generatedAt"`：`"20260917-205951"` vs `"20260917-205954"`。

任何人都可以用如下方式自行复现该结论（在仓库根目录）：

```bash
cd docs/eval-snapshots
# 归一化掉唯一的时间戳字段后比对，应无差异
diff <(sed -E 's/"generatedAt" : "[^"]*"/"generatedAt" : "X"/' snapshot-real-2026-HYBRID-20260917-205951.json) \
     <(sed -E 's/"generatedAt" : "[^"]*"/"generatedAt" : "X"/' snapshot-real-2026-HYBRID-20260917-205954.json)
```

---

## 5. 它的局限（如实交代，不美化）

- **文档语料规模小**：本次仅 `uploads/` 下的 **4 篇 PDF**（外加 2 篇历史遗留 COMPLETED 文档，共 6 篇参与构建），
  **远不是**简历中声称的「1250 篇」量级。
- **golden query 规模小**：本次为 **6 条**（每篇文档由 LLM 生成 1 条），
  **远不是**简历中声称的「50 条 Golden Queries」。
- **检索指标无参考价值**：因三个检索后端全未启动，检索四指标恒为 0（见第 2 节），
  **不能**据此判断检索模块好坏，更**不构成**「NDCG@10=0.83」这类结论的任何支撑。
- **单次运行**：仅 1 次运行、无重复采样，无法据此谈稳定性或方差。

> 本目录的产物**证明的是管线的「可跑通、可复现、结构确定」**，
> **不是**「检索/生成效果达到了某个指标」。把 0 说成效果好，或把小规模说成大样本，都是不诚实的。

---

## 6. 怎么得到「有意义的」检索指标

要把检索指标跑成有意义的值，需要在**服务器**上把检索后端真正起起来，并用足量文档重跑同一套命令：

1. 按 `docs/deployment.md` 第 3 步启动中间件：`docker compose up -d`
   （会拉起 **Elasticsearch（宿主机 `9201`）/ Milvus / Neo4j / Redis / Kafka** 等）。
2. 在服务器 `.env` 中把这些开关置为**真实可用**（`docs/deployment.md` 第 4 步有完整模板）：
   - `MILVUS_ENABLED=true`
   - `NEO4J_ENABLED=true`
   - `KAFKA_ENABLED=true`（可选，仅影响审计通道）
   - `RAG_INITIALIZER_ENABLED=true`（**必须**，否则 Milvus collection 与 ES index mapping 不会被创建）
3. 确认启动日志的能力矩阵中 Elasticsearch / Milvus / Neo4j 均为 `available=true`。
4. 上传**足量**文档（如简历声称的千级规模），重新触发处理（解析/分块/向量化/建索引）。
5. 重新执行第 4 节的 `datasets/build → tasks → run → snapshot` 同一套命令。

> 详见 `docs/deployment.md`。此时 ES 的 BM25 与 Milvus 的向量召回才会真正参与，
> 检索四指标（Recall@K / Precision@K / MRR / NDCG@K / HitRate）才会反映真实的检索质量。

---

## 7. 一句话结论

**这份快照证明的是：「评测管线端到端可跑通，并能产出结构化、可复现（且结构确定）的真实产物」；
它并**不**证明「检索效果达到了某个具体指标」—— 本机检索后端未启动，检索指标为 0 是环境所致，已如实呈现。**

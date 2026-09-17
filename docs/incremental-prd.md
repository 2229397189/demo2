# AGI Assistant 增量 PRD（A~G 缺口补齐）

| 项目 | 内容 |
|---|---|
| Language | 中文 |
| Programming Language | Java 17（Spring Boot 3.2.5）+ Vue3 + TS |
| Project Name | agi_assistant_incremental |
| 范围 | 仅描述本次增量变更，不重写全量产品文档 |

## 1. 产品目标
把简历中「已声称但未真正落地」的功能补齐为**可运行、可复现、经得起面试追问**的真实实现，并保证项目能构建为 jar 独立部署运行。

## 2. 用户故事

| ID | 用户故事 |
|---|---|
| US-1 | 作为作者，我希望评测引擎用真实 RAGAS 机制算分，以便「评测指标」经得起追问 |
| US-2 | 作为作者，我希望评测数字（如各指标得分）由真实数据集跑出并导出快照，以便证明可复现 |
| US-3 | 作为作者，我希望能在配置层切换豆包/火山方舟模型，以便做模型选型对比 |
| US-4 | 作为作者，我希望 Harness 在模型/工具失败时有真实降级链与隔离，以便体现容错能力 |
| US-5 | 作为作者，我希望审计消息被真实消费落库，以便安全审计链路闭环 |
| US-6 | 作为作者，我希望沙箱执行遵守确认门槛，以便 LLM 不能无确认触发执行 |
| US-7 | 作为作者，我希望 Planner 计划状态被真实写入并进入上下文，以便多步推理可追溯 |
| US-8 | 作为作者，我希望多用户并发时任务状态互不干扰，以便不因并发报错击穿主流程 |
| US-9 | 作为作者，我希望前端能选择 FULL 三路加权检索策略，以便使用完整混合检索能力 |

## 3. 需求池

> P0=必须实现，P1=应当实现，P2=可选。验收标准均客观可测。

| 编号 | 归属 | 优先级 | 需求描述 | 验收标准 |
|---|---|---|---|---|
| REQ-01 | A1 | P0 | `GenerationEvaluator` 实现真实 RAGAS 机制 | Faithfulness=claim 分解+逐条核验；Answer Relevancy=反向生成问题+与原问相似度；Context Precision=逐 chunk 相关性×排序加权(AP@K)；Context Recall=golden claim 覆盖率。四指标各自有独立方法且被调用 |
| REQ-02 | A2 | P0 | 接入火山方舟(Ark，OpenAI 兼容)模型并可配置切换 | 配置项可切换 Ark base url `https://ark.cn-beijing.volces.com/api/v3`；新增 provider 有非空调用方；`ark.enabled=false` 时不影响启动 |
| REQ-03 | A3 | P0 | 数据集构建与结果快照导出管线 | 支持批量导入文档构建 benchmark、生成含 `relevant_doc_ids` 的 golden query；评测结果可导出为仓库内快照文件（JSON/MD），重复跑产出结构一致 |
| REQ-04 | A4 | P1 | 检索与生成指标单元测试 | 新增测试文件覆盖四检索指标与四生成指标；`mvn test` 全绿；覆盖率不再为零 |
| REQ-05 | B1 | P0 | Harness 落地真实降级链并接入全部调用点 | 降级链含 降级模型/缓存/默认答案/局部兜底；`FallbackStrategy` 调用方≥2；4 个 `HarnessRuntime` 调用点均传入有意义的非空 fallback |
| REQ-06 | B2 | P1 | 工具维度隔离执行 | 每个工具独立隔离执行，单工具阻塞不耗尽共享线程池；构造单工具长时间阻塞时其他工具仍可正常返回 |
| REQ-07 | B3 | P1 | 统一 Tool Result Schema + 状态补齐 | 工具结果使用统一结构化 Schema（非 Map 键约定）；TIMEOUT/PARTIAL 有真实赋值路径（测试可构造触发） |
| REQ-08 | B4 | P1 | 状态机可达性补齐 | 重试时能观测到 `RETRYING` 状态（测试断言可见）；`StateMachine.remove` 有调用方 |
| REQ-09 | C1 | P0 | 并发隔离与异常兜底 | taskName 按会话/请求隔离，构造 N（≥50）并发请求不抛 `IllegalStateException`；`rag-retrieval` 调用点异常被包裹，不击穿 SSE 主流程 |
| REQ-10 | D1 | P1 | 审计消费闭环 | 新增 `@KafkaListener` 消费者并落 `audit_log` 表；Kafka 不可用时应用正常启动且降级不崩溃、记录告警日志 |
| REQ-11 | E1 | P0 | 沙箱确认门槛生效 | run_code 不再硬编码 `confirmed=true`，遵守 `sandbox.require-confirm`；未确认时被拦截，确认后才执行 |
| REQ-12 | F1 | P1 | Planner State 写入接入 | `updatePlan`/`advancePlanStep`/`completePlan` 调用方均>0；执行后 Planner State 非 null，`ContextAssembly` 拼出非空 Planner 段 |
| REQ-13 | G1 | P1 | 前端新增 FULL 策略 | `ChatView.vue` 策略选择器含 FULL 选项，选中后请求携带 FULL，后端三路加权 RRF 被触发 |
| REQ-14 | 部署 | P0 | 可构建与独立启动 | `mvn clean package` 产出可运行 jar；在无 Kafka/无 Neo4j 环境下能启动并对外提供核心接口 |

## 4. 边界与非目标

| 项 | 说明 |
|---|---|
| **诚信红线（最高优先级）** | **严禁编造「1250 篇 / 10000 chunk / NDCG 0.83」等任何数字。** 本次目标是造出**能跑出真实数字的管线**；所有指标必须由真实数据跑出，不得预设、不得写死 |
| 不做 | 不重写全量产品文档，不改动已落地的三路召回/RRF/记忆/DAG 等既有能力 |
| 不做 | 不做技术方案与架构设计（由架构师负责） |
| 不做 | 不引入简历未声称的新功能，不扩需求范围 |
| 不做 | 不保证外部 API 真实费用/配额可用（见待确认） |

## 5. 待确认问题

| 编号 | 问题 | 影响 |
|---|---|---|
| Q1 | 豆包/火山方舟 API Key 是否已具备？ | 决定 REQ-02 能否端到端联调 |
| Q2 | 是否允许真实调用外部 API 产生费用？额度上限？ | 决定评测/对比能否跑真实数据（REQ-01/02/03） |
| Q3 | 构建产物是否要求在无 Kafka/无 Neo4j 环境也可启动？ | 决定 REQ-10/REQ-14 的降级强度 |
| Q4 | benchmark 数据源与规模由谁提供？ | 决定 REQ-03 数据集能否落地 |
| Q5 | 结果快照文件落库/落仓的具体路径与格式偏好？ | 决定 REQ-03 导出规范 |

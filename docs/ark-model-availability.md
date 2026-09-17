# 火山方舟（Ark）模型可用性实测记录

> 实测时间：2026-09-17
> 实测方式：真实 HTTP 调用 `https://ark.cn-beijing.volces.com/api/v3`（非 mock、非推测）
> 账号：`2127267989`

## 一、结论速览

API Key **有效**（`GET /api/v3/models` 返回 HTTP 200，共 133 个模型、其中 64 个非 Shutdown 状态）。

但**该账号当前只开通了 1 个模型**，其余调用返回 `ModelNotOpen`。

| 模型 ID | 用途 | 实测结果 |
|---|---|---|
| `doubao-seed-2-0-pro-260215` | 对话 | ✅ **HTTP 200，可正常调用** |
| `doubao-seed-2-0-lite-260215` | 对话 | ❌ `ModelNotOpen` — 模型存在，账号未开通 |
| `doubao-seed-2-0-lite-260428` | 对话 | ❌ `ModelNotOpen` |
| `doubao-seed-2-0-mini-260215` | 对话 | ❌ `ModelNotOpen` |
| `doubao-seed-2-1-pro-260915` / `-260628` | 对话 | ❌ `ModelNotOpen` |
| `doubao-seed-2-0-code-preview-260215` | 代码 | ❌ `ModelNotOpen` |
| `doubao-embedding-large-text-250515` / `-240915` | 向量 | ❌ `InvalidEndpointOrModel.NotFound`（不可访问） |
| `doubao-embedding-vision-251215` / `-250615` | 向量 | ❌ `ModelNotOpen` |
| `glm-5-3-flash-260828` / `glm-5-2-260617` | 对话 | ❌ `ModelNotOpen` |
| `deepseek-v4-1-flash-260910` / `deepseek-v4-pro-260425` | 对话 | ❌ `ModelNotOpen` |
| `qwen3-32b-20250429` / `doubao-1-5-pro-32k-250115` | 对话 | ❌ `InvalidEndpointOrModel.NotFound` |

**两种错误码的含义不同，排障时要注意区分：**

- `ModelNotOpen` —— 模型在方舟侧存在且可用，但**你的账号没开通**。去控制台开通即可。
- `InvalidEndpointOrModel.NotFound` —— 模型 ID **不存在，或你的账号无权访问**。可能是模型 ID 写错、或该模型不开放给此账号。

## 二、简历声称的两个模型

项目简历里写了「最终选择 **Doubao-Embedding-Large** 与 **Doubao-Seed-2.0-lite**」。实测：

| 简历声称 | 方舟对应模型 ID | 当前状态 | 要做的事 |
|---|---|---|---|
| Doubao-Seed-2.0-lite | `doubao-seed-2-0-lite-260215` | `ModelNotOpen` | 控制台开通后即可实跑 |
| Doubao-Embedding-Large | `doubao-embedding-large-text-250515` | 未开通 / 不可访问 | 控制台确认该模型是否开放，或改用其它可开通的向量模型 |

**在开通之前，代码里已实现 Ark provider 的可配置切换与优雅降级**（`ArkModelProvider` / `ModelProviderRouter`），
`ARK_MODEL` 目前默认指向已验证可用的 `doubao-seed-2-0-pro-260215`，保证链路能跑通。
开通后只需把 `.env` 里的 `ARK_MODEL` 改成目标模型 ID 即可做真实的模型选型对比。

## 三、复现方式

本机已配好 key，可用一条命令复核（PowerShell）：

```powershell
$arkKey = $env:ARK_API_KEY
curl.exe -s -m 40 --noproxy "*" `
  -H "Authorization: Bearer $arkKey" `
  "https://ark.cn-beijing.volces.com/api/v3/models" `
  -o models.json -w "%{http_code}"
```

单模型探测（把 `<MODEL_ID>` 换成上表任一个）：

```powershell
$body = '{"model":"<MODEL_ID>","messages":[{"role":"user","content":"hi"}],"max_tokens":1}'
# 注意：body 文件必须是不带 BOM 的 UTF-8，带 BOM 会被方舟判为 JSON 解析失败（实测踩过）
curl.exe -s -m 45 --noproxy "*" `
  -H "Authorization: Bearer $arkKey" -H "Content-Type: application/json; charset=utf-8" `
  -d "@body.json" "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
```

项目内已有真实联调测试可直接跑（该测试默认被 surefire 排除，不会在 `mvn test` 时联网花钱）：

```bash
mvn test -Dtest=ArkModelProviderLiveTest -DfailIfNoTests=false
```

实测输出：

```
[ArkLiveTest] baseUrl=https://ark.cn-beijing.volces.com/api/v3
[ArkLiveTest] model=doubao-seed-2-0-pro-260215
[ArkLiveTest] reply=[收到]
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 6.123 s
```

## 四、两个实测踩到的坑

1. **请求体带 UTF-8 BOM 会被判为 JSON 解析失败**，返回 `{"error":{"code":"InvalidParameter","message":"we could not parse the JSON body of your request"}}`。用脚本写请求体时务必写不带 BOM 的 UTF-8。
2. **国内域名不要走代理**。本机 `HTTPS_PROXY` 指向本地 Clash，`volces.com` 直连即可（curl 用 `--noproxy "*"`；Java 侧默认不读 `HTTPS_PROXY` 环境变量，通常无需处理）。

## 五、诚信声明

本文件所有结论均来自真实 HTTP 调用，错误码原样引用，未做任何修饰或推断。
**未开通的模型一律标注为未开通，不以「预计可用」等方式暗示其可用性。**

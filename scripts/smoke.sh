set +H
set -uo pipefail
# ============================================================
#  AGI Assistant 部署冒烟自检（Linux 服务器用）
#  用法（在项目根目录执行）：bash scripts/smoke.sh
#
#  本脚本为「粘贴安全」而写：
#   - 首行 set +H 关闭交互式历史展开；
#   - 全文不含惊叹号字符（避免 event not found）；
#   - 不使用多行 if/else（改单行 test && { ...; } —— 交互式粘贴时
#     多行 if/else 会被整块丢弃且静默不执行）。
#
#  可用环境变量覆盖：
#   APP_PORT(8080) JAR_PATH(target/agi-assistant-1.0.0-SNAPSHOT.jar)
#   HEALTH_PATH(/v3/api-docs) LOG_FILE(app.log) WAIT_SECONDS(90)
#   DB_HOST(localhost) DB_PORT(3306)
# ============================================================

APP_PORT="${APP_PORT:-8080}"
JAR_PATH="${JAR_PATH:-target/agi-assistant-1.0.0-SNAPSHOT.jar}"
HEALTH_PATH="${HEALTH_PATH:-/v3/api-docs}"
LOG_FILE="${LOG_FILE:-app.log}"
WAIT_SECONDS="${WAIT_SECONDS:-90}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-3306}"
BASE_URL="http://127.0.0.1:${APP_PORT}"

echo "============================================================"
echo " AGI Assistant 部署冒烟自检 / SMOKE TEST"
echo " 端口=${APP_PORT}  探活路径=${HEALTH_PATH}  等待上限=${WAIT_SECONDS}s"
echo "============================================================"

# ---------- 1. 前置检查 ----------
echo "[1/5] 前置检查"
command -v java >/dev/null 2>&1 && echo "[OK] java 可用：$(java -version 2>&1 | head -n1)" || { echo "[FAIL] 未找到 java，请先安装 JDK 17 并确认 PATH"; exit 1; }

port_open() {
  { exec 3<>"/dev/tcp/127.0.0.1/$1"; } 2>/dev/null && { exec 3>&-; return 0; }
  return 1
}

{ exec 3<>"/dev/tcp/${DB_HOST}/${DB_PORT}"; } 2>/dev/null && { echo "[OK] MySQL ${DB_HOST}:${DB_PORT} 可达"; exec 3>&-; } || echo "[WARN] MySQL ${DB_HOST}:${DB_PORT} 不可达 —— 应用会因数据源初始化失败而启动异常，请先启动 MySQL 并核对 .env 的 DB_* 配置"

port_open "$APP_PORT" && echo "[INFO] 端口 ${APP_PORT} 已在监听（应用可能已在运行）" || echo "[INFO] 端口 ${APP_PORT} 空闲"

# ---------- 2. 准备 jar 并按需启动 ----------
echo "[2/5] 准备应用"
test -f "$JAR_PATH" || { echo "[FAIL] 未找到 jar：${JAR_PATH} —— 请先在项目根目录执行 mvn clean package"; exit 1; }
echo "[OK] 找到 jar：${JAR_PATH}"
port_open "$APP_PORT" || { echo "[INFO] 后台启动：java -jar ${JAR_PATH}（日志 -> ${LOG_FILE}）"; nohup java -jar "$JAR_PATH" >"$LOG_FILE" 2>&1 & }

# ---------- 3. 探活（轮询等待） ----------
echo "[3/5] 探活 ${BASE_URL}${HEALTH_PATH}（最长 ${WAIT_SECONDS}s）"
CODE="000"
i=0
while [ "$i" -lt "$WAIT_SECONDS" ] && [ "$CODE" = "000" ]; do
  CODE="$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}${HEALTH_PATH}" 2>/dev/null)"
  CODE="${CODE:-000}"
  [ "$CODE" = "000" ] && sleep 1
  i=$((i + 1))
done

test "$CODE" = "000" && { echo "[FAIL] 应用在 ${WAIT_SECONDS}s 内未响应 ${BASE_URL}${HEALTH_PATH}"; echo "---- 最近 40 行日志（${LOG_FILE}）----"; tail -n 40 "$LOG_FILE" 2>/dev/null; echo "[HINT] 排障：见 docs/deployment.md 的『常见故障排查表』"; exit 1; }
echo "[OK] 应用已就绪：HTTP ${CODE}（${BASE_URL}${HEALTH_PATH}，此路径不属于 /api/**，不受鉴权拦截，适合探活）"

# ---------- 4. 打印启动能力矩阵 ----------
echo "[4/5] 启动能力矩阵（StartupCapabilityLogger 输出）"
grep -F '[CAPABILITY]' "$LOG_FILE" 2>/dev/null || echo "[WARN] 日志中未找到能力矩阵（[CAPABILITY] 行），请确认应用已成功启动且日志路径正确"
echo "---- 启动完成行 ----"
grep -E 'Started AgiAssistantApplication' "$LOG_FILE" 2>/dev/null || echo "[WARN] 未找到 'Started AgiAssistantApplication' 日志行"

# ---------- 5. 结论与后续验证提示 ----------
echo "[5/5] 冒烟结果"
echo "[OK] 冒烟自检通过：进程存活 + 核心端点可访问。"
echo "[NEXT] 进一步验证关键能力（按 .env 中 AUTH_ENABLED 选择其一）："
echo "       - 鉴权开启（服务器默认 AUTH_ENABLED=true）："
echo "         TOKEN=\$(curl -s -X POST ${BASE_URL}/api/auth/login -H 'Content-Type: application/json' -d '{\"username\":\"admin\",\"password\":\"yourpass\"}' | grep -o '\"token\":\"[^\"]*\"' | cut -d'\"' -f4)"
echo "         curl -s ${BASE_URL}/api/models/providers -H \"Authorization: Bearer \$TOKEN\""
echo "       - 鉴权关闭（本地 AUTH_ENABLED=false）："
echo "         curl -s ${BASE_URL}/api/models/providers -H 'X-User-Id: 1'"
echo "       - 若 401：说明鉴权已开启但未带 token，属预期行为。"
echo "[DONE] 自检结束。"

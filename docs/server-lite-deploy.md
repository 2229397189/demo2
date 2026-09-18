# 轻量部署方案（低配服务器专用 · ≤2G 内存）

> **为什么有这份文档**：`docker-compose.prod.yml` 的全套中间件（ES + Milvus + Neo4j + Kafka + MySQL + Redis）
> 至少需要 **6–8G 内存**。若服务器只有 2G（如阿里云 2核2G / 1.8G 可用），强行启动会：
> ES / Milvus 反复 OOM 重启 → 机器卡死 → 连现有服务都受影响。
>
> **本方案思路**：复用宿主机已有组件（nginx / redis），只补装 MySQL，
> 后端直接 `java -jar` 运行，重中间件通过 `.env` 开关全部关闭 —— 由应用自身的优雅降级兜底。
>
> 实测环境：Alibaba Cloud Linux 3（Anolis），x86_64，2 核，Mem 1870MB，磁盘 32G 可用。

---

## 关闭哪些能力、影响是什么

| 中间件 | `.env` 开关 | 关闭后的行为（代码已实现降级） |
|---|---|---|
| Elasticsearch | `MILVUS_ENABLED=false`（ES 无开关，靠不可达降级） | BM25 稀疏检索返回空，检索退化为可用路 |
| Milvus | `MILVUS_ENABLED=false` | DENSE 向量召回跳过，混合检索退化 |
| Neo4j | `NEO4J_ENABLED=false` | 图谱检索恒空、图记忆跳过 |
| Kafka | `KAFKA_ENABLED=false` | 审计走 **DB 主写 + 本地日志**（本来就是主路径） |
| Docker 沙箱 | `SANDBOX_ENABLED=false` | `run_code` 返回「沙箱已禁用」，不影响其他功能 |

> ⚠️ 因此**文档状态会是 PARTIAL 而非 COMPLETED**——这正是本项目修复「假 COMPLETED」的意义：
> 前端能明确看到「部分索引未建立」，而不是假装成功。

---

## 一、清理旧项目（假设已确认不要）

> 执行前先备份（便宜的保险）。下面假设旧项目目录是 `/opt/chiron`，旧 java 进程 pid 为 25347，**请按实际替换**。

```bash
set +H

# 1.1 先确认这个 java 进程确实是旧项目（别盲杀）
ps -p 25347 -o pid,cmd
ls -la /opt/chiron

# 1.2 备份（可选但建议，几十秒的事）
tar -czf /root/backup-chiron-$(date +%Y%m%d-%H%M).tar.gz /opt/chiron 2>/dev/null && echo "备份完成"
ls -lh /root/backup-chiron-*.tar.gz

# 1.3 停掉旧 java 进程（优雅停止）
kill 25347
sleep 5
# 若仍在则强杀
ps -p 25347 >/dev/null 2>&1 && kill -9 25347

# 1.4 确认 8080 已释放
ss -lntp | grep ':8080' || echo "8080 已释放"

# 1.5 删除旧项目目录（确认 1.2 备份成功后再执行）
rm -rf /opt/chiron

# 1.6 清理旧补丁包/转储（确认无用后）
# rm -f /root/chiron-patch-0913.tar.gz /root/lq_deepseek_*.dump
```

> **保留**：`nginx`（80 端口，等下复用做前端托管 + 反代）、`redis`（6379，AGI 要用）。
> 不要卸载它们，否则还得重装。

---

## 二、装依赖

```bash
set +H

# 2.1 确认 JDK 版本（项目要求 Java 17）
java -version 2>&1 | head -2
# 若不是 17，安装：
# dnf install -y java-17-openjdk-devel
# alternatives --config java   # 选 17

# 2.2 装 MySQL（Alibaba Cloud Linux 3）
dnf install -y mysql-server
systemctl enable mysqld
systemctl start mysqld
mysqladmin --version

# 2.3 建库 + 设 root 密码（把 STRONG_PWD 换成强密码）
mysql -uroot <<'EOF'
ALTER USER 'root'@'localhost' IDENTIFIED BY 'STRONG_PWD';
CREATE DATABASE IF NOT EXISTS agi_assistant DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
EOF
mysql -uroot -p'STRONG_PWD' -e "SHOW DATABASES;" | grep agi_assistant && echo "建库成功"
```

> MySQL 内存占用较大，若仍吃紧，可在 `/etc/my.cnf.d/` 下加一个 `low.cnf`：
> ```ini
> [mysqld]
> performance_schema=OFF
> innodb_buffer_pool_size=128M
> max_connections=50
> ```
> 然后 `systemctl restart mysqld`。

---

## 三、部署后端

```bash
set +H
cd /opt
git clone https://github.com/2229397189/demo2.git agi-assistant
cd agi-assistant

# 3.1 用 lite 模板生成 .env
cp .env.lite.example .env

# 3.2 修改关键项（务必）
#   DB_PASSWORD / MYSQL_ROOT_PASSWORD  → 改成上面设的强密码
#   JWT_SECRET                          → 随机串
#   OPENAI_API_KEY                      → 智谱 key
#   EMBEDDING_API_KEY                   → DashScope key
vi .env

# 3.3 构建（跳过测试更快）
./mvnw clean package -DskipTests
ls -lh target/agi-assistant-1.0.0-SNAPSHOT.jar
```

### 3.4 配成 systemd 服务（限制内存，防止拖垮机器）

```bash
set +H
cat > /etc/systemd/system/agi-backend.service <<'EOF'
[Unit]
Description=AGI Assistant Backend
After=network.target mysqld.service redis.service

[Service]
Type=simple
User=root
WorkingDirectory=/opt/agi-assistant
ExecStart=/usr/bin/java -Xms256m -Xmx512m -jar /opt/agi-assistant/target/agi-assistant-1.0.0-SNAPSHOT.jar
Restart=on-failure
RestartSec=10
StandardOutput=append:/var/log/agi-backend.log
StandardError=append:/var/log/agi-backend.log

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable agi-backend
systemctl start agi-backend
sleep 25
systemctl status agi-backend --no-pager | head -12
```

> `-Xmx512m` 是关键：默认堆可能吃满内存导致 OOM Killer 杀掉 MySQL。

---

## 四、部署前端（复用现有 nginx）

```bash
set +H
cd /opt/agi-assistant/frontend

# 4.1 装依赖并构建（用国内源加速）
npm install --registry=https://registry.npmmirror.com
npm run build
ls -lh dist/index.html

# 4.2 部署到 nginx
mkdir -p /usr/share/nginx/agi-assistant
cp -r dist/* /usr/share/nginx/agi-assistant/
```

### 4.3 加 nginx 配置（**先备份现有配置**）

```bash
set +H
cp /etc/nginx/nginx.conf /etc/nginx/nginx.conf.bak-$(date +%Y%m%d)

cat > /etc/nginx/conf.d/agi-assistant.conf <<'EOF'
server {
    listen 8081;                      # 避开旧项目曾用过的 80 冲突；若 80 已空闲可改成 80
    server_name _;

    root /usr/share/nginx/agi-assistant;
    index index.html;

    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/javascript;

    # SPA 路由回退
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 后端 API 反代（SSE 必须关缓冲，否则流式对话会卡死）
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

        proxy_buffering off;
        proxy_cache off;
        proxy_set_header Connection '';
        proxy_read_timeout 300s;
        chunked_transfer_encoding on;
        proxy_set_header X-Accel-Buffering no;
    }
}
EOF

nginx -t && systemctl reload nginx
```

> 若想直接用 80 端口：`listen 80;` 并确保没有其他 server 占用 80。

---

## 五、验证（不是 200 就算过）

```bash
set +H

# 5.1 看能力矩阵：哪些组件真的可用
grep "\[CAPABILITY\]" /var/log/agi-backend.log | head -20

# 5.2 注册 + 登录
curl -s -X POST http://127.0.0.1:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"你的强密码"}' | head -c 300; echo

TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"你的强密码"}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
echo "token 长度=${#TOKEN}"

# 5.3 鉴权：无 token 必须 401，带 token 必须 200
curl -s -o /dev/null -w 'no-token   -> %{http_code}\n' http://127.0.0.1:8080/api/documents
curl -s -o /dev/null -w 'with-token -> %{http_code}\n' -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/documents

# 5.4 真实结果：工具列表 ≥6 个
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/agent/tools \
  | grep -o '"name":"[^"]*"' | wc -l

# 5.5 前端可达
curl -s -o /dev/null -w 'frontend  -> %{http_code}\n' http://127.0.0.1:8081/
curl -s -o /dev/null -w 'api-proxy -> %{http_code}\n' http://127.0.0.1:8081/api/models/providers
```

浏览器打开 `http://服务器IP:8081/`（用公网 IP，注意阿里云安全组要放行 8081）。

---

## 六、运维

```bash
set +H
systemctl status agi-backend
tail -f /var/log/agi-backend.log
systemctl restart agi-backend

# 改代码后重新部署
cd /opt/agi-assistant && git pull
./mvnw clean package -DskipTests
systemctl restart agi-backend
```

---

## 七、后续想上完整中间件怎么办

内存升到 **≥8G**（推荐 2核8G 或 4核8G）后，直接切回容器化方案：

```bash
set +H
cd /opt/agi-assistant
docker compose -f docker-compose.prod.yml up -d --build
```

并把 `.env` 里的 `MILVUS_ENABLED` / `NEO4J_ENABLED` / `KAFKA_ENABLED` 改为 `true`。
（那时 ES 也能正常启动，三路混合检索才真正生效。）

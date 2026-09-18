# 服务器部署与旧项目清理指南

> 适用：一台已经跑过别的项目的云服务器，现在要部署 **AGI Assistant**。
> 原则：**先只读检查 → 备份 → 再清理 → 最后部署**。任何删除动作之前，都已经给了对应的备份命令。
> 命令全部可直接复制粘贴执行。

---

## 第 0 步：只读检查（这一阶段不会改动任何东西）

先搞清楚服务器上现在有什么，**再决定删什么**。

```bash
set +H
# 0.1 看系统
cat /etc/os-release | head -3
uname -m

# 0.2 看 Docker 是否装了、版本多少
docker --version
docker compose version

# 0.3 看有哪些容器（含已停止的）
docker ps -a --format "table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}"

# 0.4 看有哪些镜像
docker images --format "table {{.Repository}}\t{{.Tag}}\t{{.Size}}"

# 0.5 看有哪些数据卷（⚠️ 卷里可能就是旧项目的数据库数据，别乱删）
docker volume ls

# 0.6 看磁盘还剩多少
df -h
```

### 0.7 端口占用检查（最关键）

AGI Assistant 需要这些端口：`3306`(MySQL) `6379`(Redis) `9092`(Kafka) `9201`(ES) `7687`(Neo4j) `19530`(Milvus) `8080`(后端) `80`(前端)。

用 `ss` 看（多数新系统自带）：

```bash
set +H
ss -lntp 2>/dev/null | grep -E ':3306|:6379|:9092|:9201|:7687|:19530|:8080|:80 '
```

> 如果 `ss` 不存在（很多精简镜像没有），改用下面这条**基于内核的、不依赖任何额外工具**的写法：
>
> ```bash
> set +H
> awk 'NR>1{split($2,a,":"); printf "%d\n", strtonum("0x" a[2])}' /proc/net/tcp | sort -n -u
> ```
> （输出的是端口号的十进制列表，看里面有没有 3306/6379/8080/80 等）

**也可以直接用 curl 探活**（最稳，不依赖 ss/netstat/lsof）：

```bash
set +H
for p in 3306 6379 9092 9201 7687 19530 8080 80; do
  code=$(curl -s -o /dev/null -m 2 -w '%{http_code}' http://127.0.0.1:$p/ 2>/dev/null)
  echo "port $p -> http=$code"
done
```
> 说明：非 HTTP 服务（MySQL/Redis/Kafka）会返回 `000`，但只要**有返回**就说明端口被占了。
> 返回 `000` 且很快失败 = 端口没人监听（可放心使用）。

### 0.8 找到旧项目在哪

```bash
set +H
ls -la /root /home /opt /srv /data 2>/dev/null
# 常见部署目录，逐个看有没有旧项目的 docker-compose.yml
find / -maxdepth 4 -name "docker-compose*.yml" -not -path "*/node_modules/*" 2>/dev/null
```

---

## 第 1 步：备份旧项目（务必做）

> ⚠️ **在删任何东西之前先备份。** 下面两个备份都很便宜，别省。

### 1.1 备份旧项目目录

```bash
set +H
# 把 OLD_DIR 换成第 0.8 步找到的真实路径（例如 /root/my-old-project）
OLD_DIR=/root/OLD_PROJECT

tar -czf /root/backup-old-project-$(date +%Y%m%d-%H%M).tar.gz "$OLD_DIR" 2>/dev/null && echo "目录备份完成" || echo "备份失败，先别往下删"
ls -lh /root/backup-old-project-*.tar.gz
```

### 1.2 备份数据库（如果旧项目用 MySQL）

```bash
set +H
# 找到旧 MySQL 容器名（第 0.3 步看到的）
docker ps -a --format '{{.Names}}' | grep -i -E 'mysql|mariadb'
```

假设容器名是 `old-mysql`，导出全部库：

```bash
set +H
docker exec old-mysql mysqldump -uroot -p'你的旧密码' --all-databases --single-transaction \
  > /root/backup-old-mysql-$(date +%Y%m%d-%H%M).sql 2>/dev/null && echo "数据库备份完成"
ls -lh /root/backup-old-mysql-*.sql
```

> 如果连不上就跳过，但**不要删那个容器**。

---

## 第 2 步：清理（两种强度，自己选）

### 方案 A（推荐，温和）：只停容器、释放端口，保留数据

适用于：你以后可能还要回滚旧项目，或旧数据还有用。

```bash
set +H
# A1. 只停止旧项目的容器（不删除，随时可以再启动）
#     把下面的名字换成第 0.3 步里看到的旧容器名
docker stop 旧容器1 旧容器2 2>/dev/null

# A2. 确认端口已释放
ss -lntp 2>/dev/null | grep -E ':3306|:8080|:80 ' || echo "端口已释放"
```

> 这样 AGI Assistant 就能用这些端口了；旧容器还在硬盘上，需要时 `docker start` 即可恢复。

### 方案 B（彻底）：删除旧容器、旧镜像、旧卷、旧目录

适用于：确定旧项目不要了。

```bash
set +H
# B1. 停止并删除指定容器（⚠️ 不要用 docker rm -f $(docker ps -aq)，那会删掉全部容器）
docker rm -f 旧容器1 旧容器2 2>/dev/null

# B2. 删除旧镜像（指定仓库名，⚠️ 不要用 docker system prune -a 一把梭）
docker rmi 旧镜像名:tag 2>/dev/null

# B3. 删除旧数据卷（⚠️ 卷删了数据就真没了，确认 1.2 步备份成功了再执行）
# docker volume rm 旧卷名
```

> **明确禁止在本服务器上直接跑这两条**（它们会无差别清空）：
> - `docker rm -f $(docker ps -aq)` —— 删光所有容器
> - `docker system prune -a --volumes` —— 删光所有未使用镜像和**所有未被容器引用的卷**
>
> 如果你确实想回收空间，在确认没有需要保留的东西后，可以跑**不带 volumes 的安全版本**：
> ```bash
> set +H
> docker system prune -f     # 只清 dangling 镜像和停止的容器，不动卷
> ```

### 2.1 释放被占用的端口（如果旧服务不是 Docker 起的）

如果某个端口被**宿主机进程**（不是容器）占着，例如旧项目直接在宿主机装了 MySQL：

```bash
set +H
# 看谁在占 3306
ss -lntp 2>/dev/null | grep ':3306'

# 停止宿主机上的旧 MySQL（名字可能是 mysql / mysqld / mariadb）
systemctl stop mysql 2>/dev/null || systemctl stop mysqld 2>/dev/null || systemctl stop mariadb 2>/dev/null
systemctl disable mysql 2>/dev/null || systemctl disable mysqld 2>/dev/null || true
```

> 或者**不删它**：让 AGI Assistant 改用别的端口（见第 3.4 步）。

---

## 第 3 步：部署 AGI Assistant

### 3.1 安装 Docker（若未装）

```bash
set +H
curl -fsSL https://get.docker.com | bash
systemctl enable docker
systemctl start docker
docker --version
```

国内服务器建议配镜像加速（否则拉镜像会很慢）：

```bash
set +H
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://hub-mirror.c.163.com",
    "https://mirror.ccs.tencentyun.com"
  ]
}
EOF
systemctl restart docker
```

### 3.2 拉取代码

```bash
set +H
cd /opt
git clone https://github.com/2229397189/demo2.git agi-assistant
cd agi-assistant
git log --oneline -1
```

### 3.3 配置 .env（**必须改密码**）

```bash
set +H
cp .env.prod.example .env
```

然后编辑 `.env`，**这几项必须改**：

```bash
set +H
# 生成随机强密码（复制输出结果填进 .env）
openssl rand -base64 24
```

需要填的关键项：

| 键 | 说明 |
|---|---|
| `DB_PASSWORD` / `MYSQL_ROOT_PASSWORD` | 两者必须**一致**，用上面生成的强密码 |
| `JWT_SECRET` | 至少 32 字节随机串 |
| `OPENAI_API_KEY` | 智谱 GLM 的 key |
| `EMBEDDING_API_KEY` | 阿里云 DashScope 的 key |
| `AUTH_ENABLED` | 生产建议 `true` |
| `SANDBOX_REQUIRE_CONFIRM` | 生产建议 `true` |

> ⚠️ 再次强调本项目的坑：**`.env` 里任何键若 `application.yml` 里没有对应 `${KEY:default}` 占位，就是死配置（填了不生效且不报错）**。模板已按真实键名写好，照抄即可。

### 3.4（可选）端口冲突时改端口

如果 `80` 或 `8080` 被旧项目占用且你不想动它，改 `docker-compose.prod.yml` 里的映射即可：

```yaml
  frontend:
    ports:
      - "8081:80"     # 宿主 8081 → 容器 80
  backend:
    ports:
      - "8082:8080"
```

### 3.5 启动中间件 + 应用

```bash
set +H
cd /opt/agi-assistant
docker compose -f docker-compose.prod.yml up -d --build
```

首次构建较慢（要编译后端 + 装前端依赖）。观察启动：

```bash
set +H
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f backend
```

### 3.6 拉沙箱镜像（否则 `run_code` 不可用）

```bash
set +H
docker pull python:3.11-slim
docker pull node:20-slim
docker pull eclipse-temurin:17-jdk
```

> 如果后端要能真正创建沙箱容器，需要让 `backend` 容器能访问 Docker。
> 在 `docker-compose.prod.yml` 的 `backend` 服务里加：
> ```yaml
>     volumes:
>       - /var/run/docker.sock:/var/run/docker.sock
> ```
> ⚠️ 挂载 docker.sock 等于给容器 root 权限，仅在可信环境使用；若不需要沙箱，保持 `SANDBOX_ENABLED=false` 更安全。

---

## 第 4 步：验证（不是"返回 200 就成功"）

### 4.1 看启动能力矩阵（最有用）

```bash
set +H
docker compose -f docker-compose.prod.yml logs backend 2>/dev/null | grep "\[CAPABILITY\]"
```

期望看到各组件 `available=true`；若某个是 `false`，`detail` 列会写明原因。

### 4.2 接口级验证（返回真实结果才算过）

```bash
set +H
# 1) 注册
curl -s -X POST http://127.0.0.1:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"改成你的强密码"}' | head -c 400; echo

# 2) 登录拿 token
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"改成你的强密码"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
echo "token len=${#TOKEN}"

# 3) 无 token 应 401
curl -s -o /dev/null -w 'no-token -> %{http_code}\n' http://127.0.0.1:8080/api/documents

# 4) 带 token 应 200 且返回结构正确
curl -s -o /dev/null -w 'with-token -> %{http_code}\n' \
  -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/documents

# 5) 工具列表应 ≥6 个
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/agent/tools \
  | grep -o '"name":"[^"]*"' | wc -l

# 6) 模型 provider 应有 activeProvider
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/models/providers | head -c 300; echo
```

### 4.3 前端

浏览器打开 `http://服务器IP/`（若改了端口则用 `http://服务器IP:8081/`）。

健康检查：

```bash
set +H
curl -s -o /dev/null -w 'frontend -> %{http_code}\n' http://127.0.0.1/
curl -s -o /dev/null -w 'api via nginx -> %{http_code}\n' http://127.0.0.1/api/models/providers
```

---

## 第 5 步：常用运维命令

```bash
set +H
cd /opt/agi-assistant

docker compose -f docker-compose.prod.yml ps                 # 看状态
docker compose -f docker-compose.prod.yml logs -f backend    # 跟日志
docker compose -f docker-compose.prod.yml restart backend    # 重启后端
docker compose -f docker-compose.prod.yml down               # 停止并移除容器（保留卷）
docker compose -f docker-compose.prod.yml up -d --build      # 改代码后重建
```

---

## 附录：出问题怎么回滚

- 旧项目目录备份：`/root/backup-old-project-*.tar.gz` → `tar -xzf` 解回去
- 旧数据库备份：`/root/backup-old-mysql-*.sql` → `docker exec -i 容器 mysql -uroot -p'密码' < 备份.sql`
- 只停未删的旧容器：`docker start 旧容器名` 即可恢复

**只要第 1 步的备份做了，任何一步删错了都能救回来。**

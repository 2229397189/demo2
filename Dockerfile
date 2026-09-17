# ============================================================
#  AGI Assistant 后端多阶段构建
#  阶段一：Maven 编译打包；阶段二：仅 JRE 运行（镜像更小）
# ============================================================

# ---------- 阶段一：构建 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先拷 pom 与 wrapper，利用 Docker 层缓存加速依赖下载
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw mvnw.cmd ./
RUN mvn -B dependency:go-offline -DskipTests || true

# 再拷源码并打包
COPY src ./src
COPY sql ./sql
RUN mvn -B clean package -DskipTests

# ---------- 阶段二：运行 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# 时区（日志时间正确性）
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 拷贝产物（Spring Boot 可执行 jar）
COPY --from=build /build/target/agi-assistant-*.jar app.jar

# 上传目录（挂载卷，见 compose）
RUN mkdir -p /app/uploads

EXPOSE 8080

# 注意：sql/init.sql 会在应用启动时由 Spring 执行（sql.init.mode=always），
# 因此镜像内不需要额外初始化步骤。.env 通过 compose 的 env_file 注入。
ENTRYPOINT ["java", "-jar", "app.jar"]

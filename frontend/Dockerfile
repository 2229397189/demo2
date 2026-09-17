# ============================================================
#  AGI Assistant 前端多阶段构建
#  阶段一：Node 构建产物；阶段二：nginx 托管 + 反向代理 /api
# ============================================================

# ---------- 阶段一：构建 ----------
FROM node:20-alpine AS build
WORKDIR /app

COPY package*.json ./
RUN npm ci --registry=https://registry.npmmirror.com || npm install --registry=https://registry.npmmirror.com

COPY . .
RUN npm run build

# ---------- 阶段二：nginx ----------
FROM nginx:1.25-alpine

# 构建产物
COPY --from=build /app/dist /usr/share/nginx/html

# nginx 配置：托管静态文件 + 把 /api 反代到后端（容器内服务名 backend:8080）
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

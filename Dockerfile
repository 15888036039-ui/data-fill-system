# Stage 1: Frontend Build
FROM node:20-alpine AS frontend-builder
WORKDIR /app/frontend
COPY frontend/package*.json ./
# 使用国内镜像加速
RUN npm install --registry=https://registry.npmmirror.com
COPY frontend/ .
RUN npm run build

# Stage 2: Backend Build
FROM maven:3.9-eclipse-temurin-17-alpine AS backend-builder
WORKDIR /app/backend
COPY backend/pom.xml .
# 这一步可以利用 Docker 缓存加速依赖下载
RUN mvn dependency:go-offline
COPY backend/src ./src
RUN mvn clean package -DskipTests

# Stage 3: Final Runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom -Duser.timezone=GMT+8"

# 从之前的阶段复制构建产物
COPY --from=backend-builder /app/backend/target/*.jar app.jar
COPY --from=frontend-builder /app/frontend/dist ./static

# 数据上传目录
RUN mkdir -p /data/upload

EXPOSE 8080

# 启动命令
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --spring.web.resources.static-locations=file:./static/,classpath:/static/"]

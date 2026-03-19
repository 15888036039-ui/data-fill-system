# Stage 1: Frontend Build
FROM node:20-alpine AS frontend-builder
WORKDIR /app/frontend
# Use mirror for faster install
COPY frontend/package*.json ./
RUN npm install --registry=https://registry.npmmirror.com
COPY frontend/ .
RUN npm run build

# Stage 2: Backend Build
FROM maven:3.9-eclipse-temurin-17-alpine AS backend-builder
WORKDIR /app/backend
# Cache dependencies separately
COPY backend/pom.xml .
RUN mvn dependency:go-offline
COPY backend/src ./src
RUN mvn clean package -DskipTests

# Stage 3: Extract Layers
FROM eclipse-temurin:17-jre-alpine AS extractor
WORKDIR /app
COPY --from=backend-builder /app/backend/target/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# Stage 4: Final Runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Standard Environment Variables
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom -Duser.timezone=GMT+8"

# Copy layers from extractor
COPY --from=extractor /app/dependencies/ ./
COPY --from=extractor /app/spring-boot-loader/ ./
COPY --from=extractor /app/snapshot-dependencies/ ./
COPY --from=extractor /app/application/ ./

# Copy frontend static files
COPY --from=frontend-builder /app/frontend/dist ./static

# Ensure upload directory exists and has correct permissions
USER root
RUN mkdir -p /data/upload && chown -R appuser:appgroup /data/upload
USER appuser

# Metadata Labels
LABEL org.opencontainers.image.title="Data Fill System" \
      org.opencontainers.image.description="A dynamic data filling system with Spring Boot and Vue.js" \
      org.opencontainers.image.version="1.0.0"

EXPOSE 8080

# Healthcheck (requires spring-boot-starter-actuator)
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep UP || exit 1

# Entrypoint using JarLauncher for layered builds
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.JarLauncher --spring.web.resources.static-locations=file:./static/,classpath:/static/"]

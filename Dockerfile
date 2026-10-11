# ---- Build stage: compile and package the application with the Maven wrapper ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Download dependencies first, in their own layer, so they are cached until pom.xml changes
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -q package -DskipTests

# ---- Runtime stage: only a JRE and the application jar ----
FROM eclipse-temurin:25-jre
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app app
USER app

COPY --from=build /workspace/target/similar-products-*.jar app.jar

EXPOSE 5000
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

# The image has no curl or wget, so the health check speaks HTTP through bash's /dev/tcp
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/5000 && printf 'GET /actuator/health HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

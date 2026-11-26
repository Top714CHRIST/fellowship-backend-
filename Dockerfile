# Stage 1: Build fat JAR with JDK 21
FROM gradle:8.10-jdk21 AS build
WORKDIR /app
COPY --chown=gradle:gradle src/main/resources .
RUN gradle buildFatJar --no-daemon

# Stage 2: Lightweight production runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/fellowship-mainframe.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

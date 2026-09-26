# --- сборка
FROM maven:3.8.6-openjdk-11 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
COPY data ./data
RUN mvn -B -q package

# --- запуск (Ubuntu 22.04 + Java 11)
FROM eclipse-temurin:11-jre-jammy
WORKDIR /app
COPY --from=build /src/target/heatroute.jar app.jar
ENV JAVA_OPTS="-Xms512m -Xmx12g"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "mkdir -p /data/tmp /data/jobs && exec java $JAVA_OPTS -jar app.jar"]

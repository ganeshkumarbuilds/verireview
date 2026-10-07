# ---------- Build stage ----------
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml .

RUN mvn -B dependency:go-offline

COPY src ./src

RUN mvn -B clean package -DskipTests


# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# Memory settings for 512MB container: use 70% of RAM, exit on OOM
ENTRYPOINT ["sh", "-c", "java -XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -jar app.jar --server.port=${PORT:-8080}"]
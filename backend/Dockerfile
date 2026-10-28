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

EXPOSE 10000

# Low-CPU container JVM flags: SerialGC, tiered compilation stop at level 1,
# 70% RAM, small stack, disable background preinitializer
ENTRYPOINT ["sh", "-c", "java -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:MaxRAMPercentage=70 -Xss512k -XX:+UseContainerSupport -Dspring.backgroundpreinitializer.ignore=true -XX:+ExitOnOutOfMemoryError -jar app.jar --server.port=${PORT:-10000}"]
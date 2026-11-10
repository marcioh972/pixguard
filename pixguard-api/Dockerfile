# Contexto de build = raiz do repositório (ver docker-compose.yml)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY pixguard-api/pom.xml pixguard-api/pom.xml
COPY mock-bank/pom.xml mock-bank/pom.xml
COPY pixguard-api/src pixguard-api/src
RUN mvn -B -q -pl pixguard-api -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 pixguard
COPY --from=build /workspace/pixguard-api/target/pixguard-api.jar app.jar
USER pixguard
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

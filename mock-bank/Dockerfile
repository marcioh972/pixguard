# Contexto de build = raiz do repositório (ver docker-compose.yml)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY pixguard-api/pom.xml pixguard-api/pom.xml
COPY mock-bank/pom.xml mock-bank/pom.xml
COPY mock-bank/src mock-bank/src
RUN mvn -B -q -pl mock-bank -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 mockbank
COPY --from=build /workspace/mock-bank/target/mock-bank.jar app.jar
USER mockbank
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]

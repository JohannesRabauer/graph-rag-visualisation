# syntax=docker/dockerfile:1

# ---- Build stage -----------------------------------------------------------
FROM maven:3.9.16-eclipse-temurin-25-noble AS build
WORKDIR /workspace

COPY pom.xml ./
COPY graphrag-core/pom.xml graphrag-core/pom.xml
COPY graphrag-adapter-neo4j/pom.xml graphrag-adapter-neo4j/pom.xml
COPY graphrag-adapter-langchain4j/pom.xml graphrag-adapter-langchain4j/pom.xml
COPY graphrag-adapter-parsing/pom.xml graphrag-adapter-parsing/pom.xml
COPY graphrag-web/pom.xml graphrag-web/pom.xml

COPY graphrag-core/src graphrag-core/src
COPY graphrag-adapter-neo4j/src graphrag-adapter-neo4j/src
COPY graphrag-adapter-langchain4j/src graphrag-adapter-langchain4j/src
COPY graphrag-adapter-parsing/src graphrag-adapter-parsing/src
COPY graphrag-web/src graphrag-web/src

RUN mvn -q -B package -DskipTests

# ---- Runtime stage ----------------------------------------------------------
FROM eclipse-temurin:25.0.4_7-jre-noble AS runtime
WORKDIR /app

COPY --from=build /workspace/graphrag-web/target/graphrag-web.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

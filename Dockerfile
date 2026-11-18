# --- build stage -----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Dependencies first: this layer is cached while only sources change.
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests clean package

# --- runtime stage ---------------------------------------------------------
FROM eclipse-temurin:21-jre

RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=build /workspace/target/wallet-api.jar /app/app.jar

USER app:app
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

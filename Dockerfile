FROM maven:3.9.8-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN useradd --system --uid 10001 appuser

COPY --from=build /app/target/email-service-1.0-SNAPSHOT.jar app.jar

USER appuser

EXPOSE 8085

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
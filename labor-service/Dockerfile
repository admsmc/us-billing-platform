# syntax=docker/dockerfile:1

FROM gradle:8.10.2-jdk21 AS build
WORKDIR /workspace

COPY gradle gradle
COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY settings.gradle.kts settings.gradle.kts
COPY build.gradle.kts build.gradle.kts
COPY gradle.properties gradle.properties

COPY shared-kernel shared-kernel
COPY payroll-domain payroll-domain
COPY web-core web-core
COPY labor-api labor-api
COPY labor-service labor-service

RUN ./gradlew --no-daemon :labor-service:bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/labor-service/build/libs/*.jar /app/app.jar
EXPOSE 8083
ENTRYPOINT ["java","-jar","/app/app.jar"]

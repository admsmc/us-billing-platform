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
COPY tax-api tax-api
COPY tax-config tax-config
COPY tax-catalog-ports tax-catalog-ports
COPY tax-impl tax-impl
COPY tax-content tax-content
COPY tax-service tax-service

RUN ./gradlew --no-daemon :tax-service:bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/tax-service/build/libs/*.jar /app/app.jar
EXPOSE 8082
ENTRYPOINT ["java","-jar","/app/app.jar"]

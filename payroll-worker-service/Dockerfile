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
COPY hr-service hr-service
COPY tax-service tax-service
COPY labor-service labor-service
COPY payroll-worker-service payroll-worker-service

RUN ./gradlew --no-daemon :payroll-worker-service:bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/payroll-worker-service/build/libs/*.jar /app/app.jar
EXPOSE 8088
ENTRYPOINT ["java","-jar","/app/app.jar"]

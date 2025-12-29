# syntax=docker/dockerfile:1

FROM gradle:8.10.2-jdk21@sha256:963d59f7f22767da4efbcf46b661361b61af5fb88b0309da1071c4234c647eba AS build
WORKDIR /workspace

# Keep container builds stable under constrained Docker Desktop memory.
ARG GRADLE_MAX_WORKERS=1
ARG ORG_GRADLE_JVMARGS="-Xms256m -Xmx512m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8"
ENV ORG_GRADLE_JVMARGS=$ORG_GRADLE_JVMARGS

COPY gradle gradle
COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY settings.gradle.kts settings.gradle.kts
COPY build.gradle.kts build.gradle.kts
COPY gradle.properties gradle.properties
COPY gradle.lockfile gradle.lockfile

COPY shared-kernel shared-kernel
COPY billing-domain billing-domain
COPY customer-api customer-api
COPY web-core web-core
COPY tenancy-core tenancy-core
COPY customer-portal-service customer-portal-service

RUN --mount=type=cache,target=/home/gradle/.gradle,uid=1000,gid=1000 \
    ./gradlew --no-daemon --max-workers=$GRADLE_MAX_WORKERS :customer-portal-service:bootJar

FROM eclipse-temurin:21-jre@sha256:b0f6befb3f2af49704998c4425cb6313c1da505648a8e78cee731531996f735d

WORKDIR /app

# Non-root runtime user for production hardening.
RUN groupadd -g 10001 app && \
    useradd -u 10001 -g 10001 -M -s /usr/sbin/nologin app

COPY --from=build --chown=10001:10001 /workspace/customer-portal-service/build/libs/*.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/tmp"

USER 10001:10001

EXPOSE 8090
ENTRYPOINT ["java","-jar","/app/app.jar"]

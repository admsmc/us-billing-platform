import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("org.springframework.boot") version "3.3.0"
    id("io.spring.dependency-management") version "1.1.5"
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":shared-kernel"))
    implementation(project(":payroll-domain"))
    implementation(project(":payroll-jackson"))
    implementation(project(":hr-api"))
    implementation(project(":hr-client"))
    implementation(project(":tax-api"))
    implementation(project(":labor-api"))
    implementation(project(":messaging-core"))
    implementation(project(":web-core"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Tracing (OTLP)
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")

    // Structured JSON logs
    runtimeOnly("net.logstash.logback:logstash-logback-encoder:8.0")

    // SQS-like work queue semantics via RabbitMQ (cloud-agnostic).
    implementation("org.springframework.boot:spring-boot-starter-amqp")

    testImplementation(kotlin("test"))
    testImplementation(project(":persistence-core"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params")

    // For cross-module tests that start services in-process
    testImplementation(project(":hr-service"))
    testImplementation(project(":tax-config"))
    testImplementation(project(":tax-catalog-ports"))
    testImplementation(project(":tax-impl"))
    testImplementation(project(":tax-content"))
    // JDBC access for seeding hr-service's H2 schema from worker-service tests.
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")

    // H2 + jOOQ + Jackson for test-local tax catalog wiring.
    testImplementation("com.h2database:h2:2.3.232")
    testImplementation("org.jooq:jooq:3.19.11")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0")

    // Flyway for running tax-service schema migrations in test-local H2 databases.
    testImplementation("org.flywaydb:flyway-core:10.14.0")
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

application {
    mainClass.set("com.example.uspayroll.worker.ApplicationKt")
}

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
    implementation(project(":billing-jackson-spring"))
    implementation(project(":shared-kernel"))
    // implementation(project(":payroll-domain")) // REMOVED Phase 3C
    implementation(project(":billing-domain"))
    implementation(project(":billing-jackson"))
    implementation(project(":messaging-core"))
    implementation(project(":web-core"))
    implementation(project(":tenancy-core"))
    implementation(project(":customer-api"))
    implementation(project(":customer-client"))
    // Reuse stable wire DTOs for tax/labor service calls.
    implementation(project(":rate-api"))
    implementation(project(":regulatory-api"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux") // For WebClient
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("jakarta.validation:jakarta.validation-api:3.1.0")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Tracing (OTLP)
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")

    // Structured JSON logs
    runtimeOnly("net.logstash.logback:logstash-logback-encoder:8.0")

    // Kafka domain events (cloud-agnostic).
    implementation("org.springframework.kafka:spring-kafka")

    // Work queue publishing / relay via RabbitMQ (cloud-agnostic).
    implementation("org.springframework.boot:spring-boot-starter-amqp")

    implementation("org.flywaydb:flyway-core:11.19.0")
    implementation("org.flywaydb:flyway-database-postgresql:11.19.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    testImplementation(kotlin("test"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(testFixtures(project(":tenancy-core")))
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("com.h2database:h2:2.3.232")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

application {
    mainClass.set("com.example.usbilling.orchestrator.PayrollOrchestratorApplicationKt")
}

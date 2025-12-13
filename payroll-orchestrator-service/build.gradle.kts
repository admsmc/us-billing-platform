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
    implementation(project(":messaging-core"))
    implementation(project(":web-core"))
    implementation(project(":hr-api"))
    implementation(project(":hr-client"))
    // Reuse stable wire DTOs for tax/labor service calls.
    implementation(project(":tax-api"))
    implementation(project(":labor-api"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

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
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("com.h2database:h2:2.3.232")
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

application {
    mainClass.set("com.example.uspayroll.orchestrator.PayrollOrchestratorApplicationKt")
}

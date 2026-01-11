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
    // implementation(project(":payroll-domain")) // REMOVED Phase 3C
    implementation(project(":billing-domain"))
    implementation(project(":customer-domain"))
    implementation(project(":customer-api"))
    implementation(project(":web-core"))
    implementation(project(":tenancy-core"))

    // Spring Boot application + web + JDBC.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux") // For WebClient in monitoring jobs
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("jakarta.validation:jakarta.validation-api:3.1.0")

    // Tracing (OTLP)
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")

    // Structured JSON logs
    runtimeOnly("net.logstash.logback:logstash-logback-encoder:8.0")

    // Jackson Kotlin module so Kotlin data classes / value classes serialize with stable property names.
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Database migrations and testing support for HR schema.
    implementation("org.flywaydb:flyway-core:11.19.0")
    implementation("org.flywaydb:flyway-database-postgresql:11.19.0")

    // Postgres driver for runtime connectivity.
    implementation("org.postgresql:postgresql:42.7.3")

    testImplementation(kotlin("test"))
    testImplementation(project(":persistence-core"))
    testImplementation(testFixtures(project(":tenancy-core")))
    testImplementation("com.h2database:h2:2.3.232")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin")
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

application {
    mainClass.set("com.example.usbilling.customer.CustomerServiceApplicationKt")
}

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
    implementation(project(":payroll-domain"))
    implementation(project(":billing-domain")) // New billing domain
    implementation(project(":billing-jackson"))
    implementation(project(":customer-api"))
    implementation(project(":customer-client"))
    implementation(project(":rate-api"))
    implementation(project(":regulatory-api"))
    implementation(project(":messaging-core"))
    implementation(project(":web-core"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("jakarta.validation:jakarta.validation-api:3.1.0")

    // Tracing (OTLP)
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")

    // Structured JSON logs
    runtimeOnly("net.logstash.logback:logstash-logback-encoder:8.0")

    // SQS-like work queue semantics via RabbitMQ (cloud-agnostic).
    implementation("org.springframework.boot:spring-boot-starter-amqp")

    // Redis caching for tax and labor standards reference data
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0")

    testImplementation(kotlin("test"))
    testImplementation(project(":persistence-core"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params")

    // For cross-module tests that start services in-process
    testImplementation(project(":customer-service"))
    testImplementation(project(":rate-config"))
    testImplementation(project(":rate-catalog-ports"))
    testImplementation(project(":rate-impl"))
    testImplementation(project(":rate-content"))
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
    mainClass.set("com.example.usbilling.worker.ApplicationKt")
}

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
    implementation(project(":billing-domain"))
    implementation(project(":customer-api"))
    implementation(project(":messaging-core"))
    implementation(project(":web-core"))

    // Spring Boot application + web + data
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Kafka for event consumption
    implementation("org.springframework.kafka:spring-kafka")
    
    // RabbitMQ for notification queuing
    implementation("org.springframework.boot:spring-boot-starter-amqp")

    // Email provider (SendGrid)
    implementation("com.sendgrid:sendgrid-java:4.10.2")
    
    // SMS provider (Twilio)
    implementation("com.twilio.sdk:twilio:10.0.0")

    // Template engine (Mustache)
    implementation("com.github.spullara.mustache.java:compiler:0.9.13")

    // Tracing (OTLP)
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")

    // Structured JSON logs
    runtimeOnly("net.logstash.logback:logstash-logback-encoder:8.0")

    // Jackson Kotlin module
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Database
    implementation("org.flywaydb:flyway-core:11.19.0")
    implementation("org.flywaydb:flyway-database-postgresql:11.19.0")
    implementation("org.postgresql:postgresql:42.7.3")

    testImplementation(kotlin("test"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2:2.3.232")
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

application {
    mainClass.set("com.example.usbilling.notification.NotificationApplicationKt")
}

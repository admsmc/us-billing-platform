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
    implementation(project(":hr-api"))
    implementation(project(":web-core"))

    // Spring Boot application + web + JDBC.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Jackson Kotlin module so Kotlin data classes / value classes serialize with stable property names.
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Database migrations and testing support for HR schema.
    implementation("org.flywaydb:flyway-core:11.19.0")
    implementation("org.flywaydb:flyway-database-postgresql:11.19.0")

    // Postgres driver for runtime connectivity.
    implementation("org.postgresql:postgresql:42.7.3")

    testImplementation(kotlin("test"))
    testImplementation(project(":persistence-core"))
    testImplementation("com.h2database:h2:2.3.232")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin")
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

application {
    mainClass.set("com.example.uspayroll.hr.HrApplicationKt")
}

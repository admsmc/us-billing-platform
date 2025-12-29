plugins {
    id("org.springframework.boot") version "3.3.0"
    id("io.spring.dependency-management") version "1.1.5"
    kotlin("jvm")
    kotlin("plugin.spring")
}

dependencies {
    // Domain models for test data construction
    implementation(project(":shared-kernel"))
    implementation(project(":billing-domain"))

    // Spring Boot Test infrastructure
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux") // WebClient for HTTP calls

    // REST API testing
    testImplementation("io.rest-assured:rest-assured:5.4.0")
    testImplementation("io.rest-assured:kotlin-extensions:5.4.0")

    // JSON processing
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Testcontainers for Docker orchestration
    testImplementation("org.testcontainers:testcontainers:1.19.7")
    testImplementation("org.testcontainers:postgresql:1.19.7")
    testImplementation("org.testcontainers:junit-jupiter:1.19.7")

    // JUnit 5
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Kotlin test support
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.mockk:mockk:1.13.10")

    // Awaitility for async testing
    testImplementation("org.awaitility:awaitility-kotlin:4.2.1")
}

tasks.test {
    useJUnitPlatform()

    // Set test timeout for long-running E2E tests
    systemProperty("junit.jupiter.execution.timeout.default", "5m")

    // Configure memory for Docker-based tests
    maxHeapSize = "1g"

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
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
    implementation(project(":labor-api"))
    implementation(project(":web-core"))
    implementation(project(":tenancy-core"))

    // Spring Boot web + JDBC for labor-service HTTP API and DB access.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Tracing (OTLP)
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")

    // Structured JSON logs
    runtimeOnly("net.logstash.logback:logstash-logback-encoder:8.0")

    // Flyway for managing Postgres schema migrations (including labor_standard).
    implementation("org.flywaydb:flyway-core:11.19.0")
    implementation("org.flywaydb:flyway-database-postgresql:11.19.0")

    // jOOQ for SQL-centric, type-safe access to the labor_standard schema.
    implementation("org.jooq:jooq:3.19.11")

    // Postgres driver for runtime connectivity.
    implementation("org.postgresql:postgresql:42.7.3")

    // CSV parsing for labor standards import
    implementation("org.apache.commons:commons-csv:1.11.0")

    // Jackson for generating JSON labor-standard config artifacts.
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0")

    testImplementation(kotlin("test"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2:2.3.232")
}

val sourceSets = extensions.getByType<SourceSetContainer>()

tasks.withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

application {
    mainClass.set("com.example.uspayroll.labor.LaborServiceApplicationKt")
}

// Convenience task to run the LaborStandardsImporter CLI via Gradle.
tasks.register<JavaExec>("runLaborStandardsImporter") {
    group = "application"
    description = "Regenerate labor-standards JSON and SQL from the CSV. Use -PlaborYear=YYYY to override the default year."

    classpath = sourceSets.getByName("main").runtimeClasspath
    mainClass.set("com.example.uspayroll.labor.tools.LaborStandardsImporter")

    val yearProp = project.findProperty("laborYear") as? String
        ?: System.getenv("LABOR_YEAR")
        ?: "2025"
    args(yearProp)
}

tasks.register<JavaExec>("validateLaborContentMetadata") {
    group = "verification"
    description = "Validate *.metadata.json sidecars for curated labor artifacts under labor-service/src/main/resources."

    // Prefer running from repo root so resolution works in CI.
    workingDir = rootProject.projectDir

    classpath = sourceSets.getByName("main").runtimeClasspath
    mainClass.set("com.example.uspayroll.labor.tools.LaborContentMetadataValidatorCli")
}

tasks.register<JavaExec>("validateGeneratedLaborArtifacts") {
    group = "verification"
    description = "Validate that generated labor standards JSON/SQL match the CSV inputs."

    // Prefer running from repo root so resolution works in CI.
    workingDir = rootProject.projectDir

    classpath = sourceSets.getByName("main").runtimeClasspath
    mainClass.set("com.example.uspayroll.labor.tools.LaborGeneratedArtifactsValidatorCli")

    val yearProp = project.findProperty("laborYear") as? String
        ?: System.getenv("LABOR_YEAR")
        ?: "2025"
    args(yearProp)
}

tasks.named("check") {
    dependsOn("validateLaborContentMetadata")
    dependsOn("validateGeneratedLaborArtifacts")
}

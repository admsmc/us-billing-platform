import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
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

    // Spring Boot web + JDBC for tax-service HTTP API and DB access.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // Flyway for managing Postgres schema migrations (including tax_rule).
    implementation("org.flywaydb:flyway-core")

    // jOOQ for SQL-centric, type-safe access to the tax_rule schema.
    implementation("org.jooq:jooq:3.19.11")
    // Postgres driver for runtime connectivity; actual DataSource/DSLContext
    // configuration will be provided by the hosting service.
    implementation("org.postgresql:postgresql:42.7.3")

    // Logging API; bindings are provided by the hosting application.
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Jackson for reading tax rule configuration files managed in Git.
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0")

    // CSV parsing for state income tax import.
    implementation("org.apache.commons:commons-csv:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2:2.3.232")

    // Testcontainers for verifying Boot + Flyway startup against a real Postgres instance.
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

val sourceSets = extensions.getByType<SourceSetContainer>()

tasks.withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

application {
    mainClass.set("com.example.uspayroll.tax.TaxServiceApplicationKt")
}
// Convenience task to run the StateIncomeTaxImporter CLI via Gradle.
tasks.register<JavaExec>("runStateIncomeTaxImporter") {
    group = "application"
    description = "Generate state income tax JSON from CSV. Use -PtaxYear=YYYY to override the default year."

    classpath = sourceSets.getByName("main").runtimeClasspath
    mainClass.set("com.example.uspayroll.tax.tools.StateIncomeTaxImporter")

    val yearProp = project.findProperty("taxYear") as? String
        ?: System.getenv("TAX_YEAR")
        ?: "2025"
    args(yearProp)
}

// Validate all TaxRuleFile JSON documents under src/main/resources/tax-config.
tasks.register<JavaExec>("validateTaxConfig") {
    group = "verification"
    description = "Validate tax rule JSON config files under src/main/resources/tax-config."

    classpath = sourceSets.getByName("main").runtimeClasspath
    mainClass.set("com.example.uspayroll.tax.tools.TaxConfigValidatorCli")
}

// Generate 2025 federal Pub. 15-T biweekly wage-bracket JSON from curated CSV.
// This is a pure data build step and does not start the Spring Boot application.
tasks.register<JavaExec>("generateFederal2025BiweeklyWageBrackets") {
    group = "application"
    description = "Generate 2025 federal Pub. 15-T biweekly wage-bracket TaxRuleFile JSON from IRS-derived CSV."

    classpath = sourceSets.getByName("main").runtimeClasspath
    mainClass.set("com.example.uspayroll.tax.tools.WageBracketCsvImporter")

    args(
        "--wageBracketCsv=wage-bracket-2025-biweekly.csv",
        "--output=tax-config/federal-2025-pub15t-wage-bracket-biweekly.json",
        "--baseIdPrefix=US_FED_FIT_2025_PUB15T_WB",
        "--effectiveFrom=2025-01-01",
        "--effectiveTo=9999-12-31",
    )
}

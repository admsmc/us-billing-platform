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
    implementation(project(":web-core"))
    implementation(project(":tenancy-core"))
    implementation(project(":rate-api"))
    implementation(project(":rate-config"))
    implementation(project(":rate-catalog-ports"))
    implementation(project(":rate-impl"))
    implementation(project(":rate-content"))

    // Spring Boot web + JDBC for tax-service HTTP API and DB access.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("jakarta.validation:jakarta.validation-api:3.1.0")

    // Tracing (OTLP)
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")

    // Structured JSON logs
    runtimeOnly("net.logstash.logback:logstash-logback-encoder:8.0")

    // Flyway for managing Postgres schema migrations (including tax_rule).
    implementation("org.flywaydb:flyway-core:11.19.0")
    implementation("org.flywaydb:flyway-database-postgresql:11.19.0")

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
    testImplementation(project(":persistence-core"))
    testImplementation(testFixtures(project(":tenancy-core")))
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
    mainClass.set("com.example.usbilling.tax.TaxServiceApplicationKt")
}
// Convenience task to run the StateIncomeTaxImporter CLI via Gradle.
tasks.register<JavaExec>("runStateIncomeTaxImporter") {
    group = "application"
    description = "Generate state income tax JSON from CSV. Use -PtaxYear=YYYY to override the default year."

    // Ensure TaxContentPaths can find tax-content/src/main/resources.
    workingDir = rootProject.projectDir

    classpath = sourceSets.getByName("main").runtimeClasspath
    mainClass.set("com.example.usbilling.tax.tools.StateIncomeTaxImporter")

    val yearProp = project.findProperty("taxYear") as? String
        ?: System.getenv("TAX_YEAR")
        ?: "2025"
    args(yearProp)
}

// Convenience task to import curated tax-config JSON files into the tax_rule table.
tasks.register<JavaExec>("importTaxConfigToDb") {
    group = "application"
    description = "Import curated tax-config JSON into Postgres tax_rule. Requires TAX_DB_URL/TAX_DB_USERNAME/TAX_DB_PASSWORD. Use -PtaxYear=YYYY and -Ptruncate=true for idempotent local seeding."

    workingDir = rootProject.projectDir

    classpath = sourceSets.getByName("main").runtimeClasspath
    mainClass.set("com.example.usbilling.tax.tools.TaxConfigDbImporterCli")

    val yearProp = project.findProperty("taxYear") as? String
        ?: System.getenv("TAX_YEAR")
        ?: "2025"

    val truncateProp = project.findProperty("truncate") as? String
        ?: System.getenv("TAX_IMPORT_TRUNCATE")
        ?: "false"

    args(yearProp, truncateProp)
}

// Validate all TaxRuleFile JSON documents under src/main/resources/tax-config.
tasks.register<JavaExec>("validateTaxConfig") {
    group = "verification"
    description = "Validate tax rule JSON config files under src/main/resources/tax-config."

    // Ensure TaxContentPaths can find tax-content/src/main/resources.
    workingDir = rootProject.projectDir

    classpath = sourceSets.getByName("main").runtimeClasspath
    mainClass.set("com.example.usbilling.tax.tools.TaxConfigValidatorCli")
}

// Validate metadata sidecars for curated tax-content CSV inputs.
tasks.register<JavaExec>("validateTaxContentMetadata") {
    group = "verification"
    description = "Validate *.metadata.json sidecars for curated CSV inputs under tax-content/src/main/resources."

    // Ensure TaxContentPaths can find tax-content/src/main/resources.
    workingDir = rootProject.projectDir

    classpath = sourceSets.getByName("main").runtimeClasspath
    mainClass.set("com.example.usbilling.tax.tools.TaxContentMetadataValidatorCli")
}

tasks.register<JavaExec>("validateGeneratedTaxArtifacts") {
    group = "verification"
    description = "Validate that generated tax-config JSON artifacts match the curated CSV inputs."

    workingDir = rootProject.projectDir

    classpath = sourceSets.getByName("main").runtimeClasspath
    mainClass.set("com.example.usbilling.tax.tools.GeneratedTaxArtifactsValidatorCli")

    val yearProp = project.findProperty("taxYear") as? String
        ?: System.getenv("TAX_YEAR")
        ?: "2025"
    args(yearProp)
}

tasks.named("check") {
    dependsOn("validateTaxConfig")
    dependsOn("validateTaxContentMetadata")
    dependsOn("validateGeneratedTaxArtifacts")
}

// Generate federal Pub. 15-T biweekly wage-bracket JSON from curated CSV.
// This is a pure data build step and does not start the Spring Boot application.
//
// Use -PtaxYear=YYYY to control both input CSV name and output JSON name.
tasks.register<JavaExec>("generateFederalPub15TWageBracketBiweekly") {
    group = "application"
    description = "Generate federal Pub. 15-T biweekly wage-bracket TaxRuleFile JSON from IRS-derived CSV. Use -PtaxYear=YYYY."

    // Ensure TaxContentPaths can find tax-content/src/main/resources.
    workingDir = rootProject.projectDir

    classpath = sourceSets.getByName("main").runtimeClasspath
    mainClass.set("com.example.usbilling.tax.tools.WageBracketCsvImporter")

    val yearProp = project.findProperty("taxYear") as? String
        ?: System.getenv("TAX_YEAR")
        ?: "2025"

    args(
        "--wageBracketCsv=wage-bracket-$yearProp-biweekly.csv",
        "--output=tax-config/federal-$yearProp-pub15t-wage-bracket-biweekly.json",
        "--baseIdPrefix=US_FED_FIT_${yearProp}_PUB15T_WB",
        "--effectiveFrom=$yearProp-01-01",
        "--effectiveTo=9999-12-31",
    )
}

// Back-compat alias for the original 2025-specific task name.
tasks.register<JavaExec>("generateFederal2025BiweeklyWageBrackets") {
    group = "application"
    description = "Generate 2025 federal Pub. 15-T biweekly wage-bracket TaxRuleFile JSON from IRS-derived CSV (alias)."

    workingDir = rootProject.projectDir

    classpath = sourceSets.getByName("main").runtimeClasspath
    mainClass.set("com.example.usbilling.tax.tools.WageBracketCsvImporter")

    args(
        "--wageBracketCsv=wage-bracket-2025-biweekly.csv",
        "--output=tax-config/federal-2025-pub15t-wage-bracket-biweekly.json",
        "--baseIdPrefix=US_FED_FIT_2025_PUB15T_WB",
        "--effectiveFrom=2025-01-01",
        "--effectiveTo=9999-12-31",
    )
}

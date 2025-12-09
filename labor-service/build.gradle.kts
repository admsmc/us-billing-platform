import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
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

    // Spring context for dependency injection in service modules. Domain
    // modules remain framework-free.
    implementation("org.springframework:spring-context:6.1.5")
    implementation("org.springframework:spring-jdbc:6.1.5")

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
}

val sourceSets = extensions.getByType<SourceSetContainer>()

tasks.withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
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

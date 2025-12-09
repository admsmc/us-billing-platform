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

    // jOOQ for SQL-centric, type-safe access to the tax_rule schema.
    implementation("org.jooq:jooq:3.19.11")
    // Postgres driver for runtime connectivity; actual DataSource/DSLContext
    // configuration will be provided by the hosting service.
    implementation("org.postgresql:postgresql:42.7.3")

    // Jackson for reading tax rule configuration files managed in Git.
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0")

    // CSV parsing for state income tax import.
    implementation("org.apache.commons:commons-csv:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation("com.h2database:h2:2.3.232")
}

val sourceSets = extensions.getByType<SourceSetContainer>()

tasks.withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
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

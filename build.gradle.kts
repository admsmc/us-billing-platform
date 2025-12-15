import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.spring") version "2.0.21" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.3.0" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("me.champeau.jmh") version "0.7.2" apply false

    // Supply-chain / security tooling
    id("org.owasp.dependencycheck") version "12.1.9"
    id("org.cyclonedx.bom") version "1.8.2" apply false

    jacoco
}

allprojects {
    group = "com.example.uspayroll"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

// Supply-chain hardening: lock resolved dependency versions.
// Lockfiles are written by running Gradle with `--write-locks`.
dependencyLocking {
    lockAllConfigurations()
    lockMode.set(org.gradle.api.artifacts.dsl.LockMode.STRICT)
}

subprojects {
    // Supply-chain hardening: lock resolved dependency versions per module.
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(org.gradle.api.artifacts.dsl.LockMode.STRICT)
    }

    // Only apply linting/static analysis/coverage to Kotlin modules.
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        apply(plugin = "io.gitlab.arturbosch.detekt")
        apply(plugin = "jacoco")

        // Kotlin + Spring Boot: open/proxy-friendly classes (@Configuration, @Component, etc.).
        pluginManager.withPlugin("org.springframework.boot") {
            apply(plugin = "org.jetbrains.kotlin.plugin.spring")
        }

        // Build hygiene: consistent Kotlin compiler targeting + opt-in warnings-as-errors.
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                allWarningsAsErrors.set((project.findProperty("warningsAsErrors") as? String) == "true")
            }
        }

        // Ensure JUnit 5 engine is used consistently across modules.
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }

        // Make kotlin.test.* annotations work on JUnit Platform.
        dependencies {
            add("testImplementation", kotlin("test-junit5"))
        }

        extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension>("ktlint") {
            // Keep the initial setup minimal; tune rules as the repo evolves.
            outputToConsole.set(true)
            ignoreFailures.set(false)
        }

        extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension>("detekt") {
            buildUponDefaultConfig = true
            config.setFrom(rootProject.files("config/detekt/detekt.yml"))
            // Keep baselines per module to avoid concurrent writes in a multi-module build.
            baseline = rootProject.file("config/detekt/baseline/${project.name}.xml")
            parallel = true
            ignoreFailures = false
        }

        // JaCoCo: per-module reports + enforced minimum coverage.
        extensions.configure<JacocoPluginExtension>("jacoco") {
            toolVersion = "0.8.12"
        }

        // Skip coverage for modules that have no main sources yet.
        val hasMainSources = fileTree("src/main").matching {
            include("**/*.kt", "**/*.java")
        }.files.isNotEmpty()

        val excludes = listOf(
            "**/*Application*",
            "**/config/**",
            "**/*Dto*",
            "**/dto/**",
            "**/*Configuration*",
        )

        // The JaCoCo plugin creates these tasks for JVM projects; we configure them.
        // For modules without main sources, we disable coverage verification.
        val sourceSets = extensions.getByType<SourceSetContainer>()
        val mainSourceSet = sourceSets.named("main").get()
        val testTask = tasks.named<Test>("test")

        tasks.named<JacocoReport>("jacocoTestReport") {
            dependsOn(testTask)

            reports {
                xml.required.set(true)
                html.required.set(true)
                csv.required.set(false)
            }

            classDirectories.setFrom(
                mainSourceSet.output.classesDirs.asFileTree.matching {
                    exclude(excludes)
                },
            )
            sourceDirectories.setFrom(mainSourceSet.allSource.srcDirs)
            executionData.setFrom(fileTree(buildDir).include("jacoco/test.exec", "jacoco/test*.exec"))

            enabled = hasMainSources
        }

        tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
            dependsOn(testTask)

            classDirectories.setFrom(
                mainSourceSet.output.classesDirs.asFileTree.matching {
                    exclude(excludes)
                },
            )
            executionData.setFrom(fileTree(buildDir).include("jacoco/test.exec", "jacoco/test*.exec"))

            // Thresholds (tunable): start conservative, then raise per module over time.
            // You can override per-run with -PjacocoMinLine=0.65
            val defaultMinLine = when (project.name) {
                "payroll-domain" -> 0.70
                "tax-service" -> 0.50
                else -> 0.30
            }
            val minLine = (project.findProperty("jacocoMinLine") as? String)?.toDoubleOrNull() ?: defaultMinLine

            violationRules {
                rule {
                    element = "BUNDLE"
                    limit {
                        counter = "LINE"
                        value = "COVEREDRATIO"
                        minimum = minLine.toBigDecimal()
                    }
                }
            }

            enabled = hasMainSources
        }

        // Ensure `./gradlew check` enforces coverage for modules that have main sources.
        tasks.named("check") {
            if (hasMainSources) {
                dependsOn("jacocoTestCoverageVerification")
            }
        }

        // Ensure `./gradlew check` runs lint + static analysis.
        tasks.named("check") {
            dependsOn("ktlintCheck")
            dependsOn("detekt")
        }
    }

    // Build hygiene: consistent Java compilation targeting.
    tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

// ----------------------------
// Security and SBOM tooling
// ----------------------------

dependencyCheck {
    // Fail the build when a dependency has a CVSS score >= this value.
    failBuildOnCVSS = 7.0f

    // Allow tuning via env; improves performance and avoids NVD rate limits.
    nvd.apiKey = System.getenv("NVD_API_KEY")

    // Keep noise manageable; teams typically start with HIGH/CRITICAL and tighten over time.
    analyzers.assemblyEnabled = false

    suppressionFile = "${rootProject.projectDir}/config/dependency-check/suppressions.xml"

    formats = listOf("HTML", "JSON")
    outputDirectory.set(layout.buildDirectory.dir("reports/dependency-check"))

    // Aggregate report is the repo-level artifact we gate on.
    scanConfigurations = listOf("runtimeClasspath")
}

val sbomProjects = listOf(
    project(":edge-service"),
    project(":payroll-worker-service"),
    project(":payroll-orchestrator-service"),
    project(":hr-service"),
    project(":tax-service"),
    project(":labor-service"),
    project(":payments-service"),
    project(":reporting-service"),
)

sbomProjects.forEach { p ->
    p.pluginManager.apply("org.cyclonedx.bom")

    // Configure via Groovy builder to avoid compile-time coupling to plugin classes.
    p.tasks.named("cyclonedxBom").configure {
        withGroovyBuilder {
            setProperty("includeConfigs", listOf("runtimeClasspath"))
            setProperty("skipConfigs", listOf("testRuntimeClasspath"))
            setProperty("includeLicenseText", false)
            setProperty("outputFormat", "json")
            setProperty("outputName", "${p.name}-sbom")
            setProperty("destination", p.file("build/reports/sbom"))
        }
    }
}

tasks.register("sbomAll") {
    dependsOn(sbomProjects.map { "${it.path}:cyclonedxBom" })
}

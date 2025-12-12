import org.gradle.api.tasks.compile.JavaCompile
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

    // Jackson annotations only (no Spring dependency) to support polymorphic JSON
    // for a small set of domain types that cross service boundaries.
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.17.0")

    testImplementation(kotlin("test"))
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

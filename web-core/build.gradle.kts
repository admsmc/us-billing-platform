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
    implementation("org.springframework:spring-context:6.1.8")
    implementation("org.springframework:spring-web:6.1.8")
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.0.0")

    implementation("org.slf4j:slf4j-api:2.0.16")

    testImplementation(kotlin("test"))
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

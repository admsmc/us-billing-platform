import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("java-test-fixtures")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":web-core"))
    implementation(project(":persistence-core"))

    implementation("org.springframework:spring-jdbc:6.1.8")
    implementation("org.springframework:spring-webmvc:6.1.8")

    // DataSource impl used by routing map.
    implementation("com.zaxxer:HikariCP:5.1.0")

    implementation("org.slf4j:slf4j-api:2.0.16")

    // Optional at compile time (used by interceptor signatures).
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.0.0")

    testFixturesImplementation("org.springframework:spring-test:6.1.8")

    testImplementation(kotlin("test"))
    testImplementation("org.springframework:spring-test:6.1.8")
    testImplementation("com.h2database:h2:2.3.232")
    testImplementation("jakarta.servlet:jakarta.servlet-api:6.0.0")
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

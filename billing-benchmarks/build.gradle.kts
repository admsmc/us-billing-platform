import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("me.champeau.jmh")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    // implementation(project(":payroll-domain")) // REMOVED Phase 3C
    implementation(project(":shared-kernel"))
}

jmh {
    // Keep results stable-ish while still reasonably fast locally.
    fork.set(1)
    warmupIterations.set(5)
    iterations.set(10)
    // Emit JSON so we can diff results over time.
    resultFormat.set("JSON")
}

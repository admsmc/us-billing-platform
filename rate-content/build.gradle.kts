plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// resources-only module
sourceSets {
    main {
        java.srcDirs(emptyList<String>())
    }
}

package com.example.uspayroll.tax.tools

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolve the filesystem path to the shared tax content resources directory.
 *
 * This supports two modes:
 * - Preferred: repo-root has tax-content/src/main/resources
 * - Fallback: local module has src/main/resources
 */
internal object TaxContentPaths {

    fun resourcesDir(): Path {
        val cwd = Paths.get("").toAbsolutePath()

        // If invoked from repo root (Gradle/CI), tax-content should be present.
        val shared = cwd.resolve("tax-content/src/main/resources")
        if (Files.exists(shared)) return shared

        // If invoked from inside :tax-service project dir.
        val local = cwd.resolve("src/main/resources")
        if (Files.exists(local)) return local

        // Last-resort: walk up a few levels trying to find repo root.
        var p: Path? = cwd
        repeat(6) {
            val candidate = p?.resolve("tax-content/src/main/resources")
            if (candidate != null && Files.exists(candidate)) return candidate
            p = p?.parent
        }

        return shared
    }
}

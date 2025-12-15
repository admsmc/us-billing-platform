package com.example.uspayroll.labor.tools

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * Command-line utility that validates *.metadata.json sidecars for curated labor
 * artifacts under labor-service/src/main/resources.
 */
object LaborContentMetadataValidatorCli {

    @JvmStatic
    fun main(args: Array<String>) {
        val resourcesDir = resolveLaborResourcesDir()

        if (!Files.exists(resourcesDir)) {
            println("No labor-service resources directory found at $resourcesDir; nothing to validate.")
            return
        }

        val mapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        val artifacts = Files.list(resourcesDir)
            .filter { Files.isRegularFile(it) }
            .filter { p ->
                val n = p.fileName.toString()
                (n.endsWith(".csv") || n.endsWith(".json") || n.endsWith(".sql")) && !n.endsWith(".metadata.json")
            }
            .sorted()
            .toList()

        if (artifacts.isEmpty()) {
            println("No labor artifacts found in $resourcesDir; nothing to validate.")
            return
        }

        val errors = mutableListOf<String>()

        artifacts.forEach { artifactPath ->
            val fileName = artifactPath.fileName.toString()
            val sidecar = artifactPath.resolveSibling(fileName + ".metadata.json")

            if (!Files.exists(sidecar)) {
                errors += "Missing metadata sidecar for $fileName: ${sidecar.fileName}"
                return@forEach
            }

            val meta = mapper.readTree(Files.readString(sidecar))
            val schemaVersion = meta.get("schemaVersion")?.asInt()
            if (schemaVersion != 1) {
                errors += "$fileName: schemaVersion must be 1 (found $schemaVersion)"
            }

            val domain = meta.get("domain")?.asText()
            if (domain != "labor") {
                errors += "$fileName: domain must be 'labor' (found '$domain')"
            }

            val artifactNode = meta.get("artifact")
            val declaredSha = artifactNode?.get("sha256")?.asText().orEmpty()
            if (declaredSha.isBlank()) {
                errors += "$fileName: artifact.sha256 is required"
            } else {
                val computedSha = sha256Hex(Files.readAllBytes(artifactPath))
                if (!declaredSha.equals(computedSha, ignoreCase = true)) {
                    errors += "$fileName: artifact.sha256 does not match file contents"
                }
            }

            val source = meta.get("source")
            if (source == null || source.isMissingNode) {
                errors += "$fileName: source is required"
            } else {
                if (source.get("kind")?.asText().isNullOrBlank()) errors += "$fileName: source.kind is required"

                val sourceId = source.get("id")?.asText()
                if (sourceId.isNullOrBlank()) {
                    errors += "$fileName: source.id is required"
                } else if (sourceId.equals("TBD", ignoreCase = true)) {
                    errors += "$fileName: source.id must not be 'TBD'"
                }

                if (source.get("revision_date")?.asText().isNullOrBlank()) errors += "$fileName: source.revision_date is required"
            }

            val approvals = meta.get("approvals")
            if (approvals == null || !approvals.isArray || approvals.size() == 0) {
                errors += "$fileName: approvals must be a non-empty array"
            }

            if (errors.none { it.startsWith(fileName + ":") || it.contains("$fileName:") || it.contains("for $fileName") }) {
                println("OK: ${artifactPath.fileName}")
            }
        }

        if (errors.isNotEmpty()) {
            println("\nLabor metadata validation FAILED:")
            errors.forEach { println("  - $it") }
            System.exit(1)
        } else {
            println("\nLabor metadata validation succeeded: ${artifacts.size} file(s) checked, no errors.")
        }
    }

    private fun resolveLaborResourcesDir(): Path {
        val cwd = Paths.get("").toAbsolutePath()

        val shared = cwd.resolve("labor-service/src/main/resources")
        if (Files.exists(shared)) return shared

        val local = cwd.resolve("src/main/resources")
        if (Files.exists(local)) return local

        var p: Path? = cwd
        repeat(6) {
            val candidate = p?.resolve("labor-service/src/main/resources")
            if (candidate != null && Files.exists(candidate)) return candidate
            p = p?.parent
        }

        return shared
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}

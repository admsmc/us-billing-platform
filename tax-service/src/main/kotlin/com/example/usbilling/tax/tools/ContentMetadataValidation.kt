package com.example.usbilling.tax.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal object ContentMetadataValidation {

    data class ValidationError(
        val message: String,
        val artifactFileName: String? = null,
    )

    fun validateSidecar(mapper: ObjectMapper, artifactPath: Path, expectedDomain: String): List<ValidationError> {
        val fileName = artifactPath.fileName.toString()
        val sidecar = artifactPath.resolveSibling(fileName + ".metadata.json")

        if (!Files.exists(sidecar)) {
            return listOf(ValidationError("Missing metadata sidecar: ${sidecar.fileName}", fileName))
        }

        val metaJson = Files.readString(sidecar)
        val meta: JsonNode = try {
            mapper.readTree(metaJson)
        } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
            return listOf(ValidationError("Invalid JSON in metadata sidecar ${sidecar.fileName}: ${e.message}", fileName))
        }

        val errors = mutableListOf<ValidationError>()

        val schemaVersion = meta.get("schemaVersion")?.asInt()
        if (schemaVersion != 1) {
            errors += ValidationError("schemaVersion must be 1 (found $schemaVersion)", fileName)
        }

        val domain = meta.get("domain")?.asText()
        if (domain != expectedDomain) {
            errors += ValidationError("domain must be '$expectedDomain' (found '$domain')", fileName)
        }

        val contentId = meta.get("contentId")?.asText().orEmpty()
        if (contentId.isBlank()) {
            errors += ValidationError("contentId is required", fileName)
        }

        val artifact = meta.get("artifact")
        if (artifact == null || artifact.isMissingNode) {
            errors += ValidationError("artifact is required", fileName)
            return errors
        }

        val declaredSha = artifact.get("sha256")?.asText().orEmpty()
        if (declaredSha.isBlank()) {
            errors += ValidationError("artifact.sha256 is required", fileName)
        } else {
            val computedSha = sha256Hex(Files.readAllBytes(artifactPath))
            if (!declaredSha.equals(computedSha, ignoreCase = true)) {
                errors += ValidationError("artifact.sha256 does not match file contents", fileName)
            }
        }

        val declaredPath = artifact.get("path")?.asText().orEmpty()
        if (declaredPath.isBlank() || !declaredPath.endsWith(fileName)) {
            errors += ValidationError("artifact.path must end with '$fileName'", fileName)
        }

        val source = meta.get("source")
        if (source == null || source.isMissingNode) {
            errors += ValidationError("source is required", fileName)
        } else {
            if (source.get("kind")?.asText().isNullOrBlank()) {
                errors += ValidationError("source.kind is required", fileName)
            }
            val sourceId = source.get("id")?.asText()
            if (sourceId.isNullOrBlank()) {
                errors += ValidationError("source.id is required", fileName)
            } else if (sourceId.equals("TBD", ignoreCase = true)) {
                errors += ValidationError("source.id must not be 'TBD'", fileName)
            }
            if (source.get("revision_date")?.asText().isNullOrBlank()) {
                errors += ValidationError("source.revision_date is required", fileName)
            }
        }

        val approvals = meta.get("approvals")
        if (approvals == null || !approvals.isArray) {
            errors += ValidationError("approvals array is required", fileName)
        } else if (approvals.size() == 0) {
            errors += ValidationError("approvals must be non-empty", fileName)
        } else {
            approvals.forEach { a ->
                if (a.get("role")?.asText().isNullOrBlank()) {
                    errors += ValidationError("approvals[].role is required", fileName)
                }
                if (a.get("reference")?.asText().isNullOrBlank()) {
                    errors += ValidationError("approvals[].reference is required", fileName)
                }
                if (a.get("approved_at")?.asText().isNullOrBlank()) {
                    errors += ValidationError("approvals[].approved_at is required", fileName)
                }
            }
        }

        return errors
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}

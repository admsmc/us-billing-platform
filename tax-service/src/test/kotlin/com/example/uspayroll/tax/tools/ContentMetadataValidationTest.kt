package com.example.uspayroll.tax.tools

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentMetadataValidationTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `validateSidecar succeeds when schema and sha256 match`() {
        val dir = Files.createTempDirectory("content-metadata-test")
        val artifact = dir.resolve("example.csv")
        Files.writeString(artifact, "hello")

        val sha = sha256Hex("hello".toByteArray())

        val meta = """
            {
              "schemaVersion": 1,
              "contentId": "TAX_EXAMPLE",
              "domain": "tax",
              "artifact": {
                "path": "tax-content/src/main/resources/example.csv",
                "sha256": "$sha",
                "media_type": "text/csv"
              },
              "coverage": {
                "year": 2025,
                "effective_from": "2025-01-01",
                "effective_to": "9999-12-31",
                "jurisdictions": null
              },
              "source": {
                "kind": "INTERNAL",
                "id": "SRC_TAX_EXAMPLE",
                "revision_date": "2025-12-14",
                "checksum_sha256": null
              },
              "approvals": [
                {
                  "role": "ENGINEERING",
                  "reference": "unit-test",
                  "approved_at": "2025-12-14",
                  "name": null
                }
              ]
            }
        """.trimIndent()

        Files.writeString(dir.resolve("example.csv.metadata.json"), meta)

        val errors = ContentMetadataValidation.validateSidecar(mapper, artifact, expectedDomain = "tax")
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
    }

    @Test
    fun `validateSidecar fails when sidecar is missing`() {
        val dir = Files.createTempDirectory("content-metadata-test")
        val artifact = dir.resolve("missing.csv")
        Files.writeString(artifact, "hello")

        val errors = ContentMetadataValidation.validateSidecar(mapper, artifact, expectedDomain = "tax")
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.first().message.contains("Missing metadata sidecar"))
    }

    @Test
    fun `validateSidecar fails when sha256 mismatches`() {
        val dir = Files.createTempDirectory("content-metadata-test")
        val artifact = dir.resolve("bad.csv")
        Files.writeString(artifact, "hello")

        val meta = """
            {
              "schemaVersion": 1,
              "contentId": "TAX_BAD",
              "domain": "tax",
              "artifact": {
                "path": "tax-content/src/main/resources/bad.csv",
                "sha256": "deadbeef",
                "media_type": "text/csv"
              },
              "coverage": { "year": 2025, "effective_from": "2025-01-01", "effective_to": "9999-12-31", "jurisdictions": null },
              "source": { "kind": "INTERNAL", "id": "SRC_TAX_BAD", "revision_date": "2025-12-14", "checksum_sha256": null },
              "approvals": [ { "role": "ENGINEERING", "reference": "unit-test", "approved_at": "2025-12-14", "name": null } ]
            }
        """.trimIndent()

        Files.writeString(dir.resolve("bad.csv.metadata.json"), meta)

        val errors = ContentMetadataValidation.validateSidecar(mapper, artifact, expectedDomain = "tax")
        assertTrue(errors.any { it.message.contains("artifact.sha256 does not match") })
    }

    @Test
    fun `validateSidecar fails when domain mismatches`() {
        val dir = Files.createTempDirectory("content-metadata-test")
        val artifact = dir.resolve("example.json")
        Files.writeString(artifact, "{}")

        val sha = sha256Hex("{}".toByteArray())

        val meta = """
            {
              "schemaVersion": 1,
              "contentId": "LABOR_EXAMPLE",
              "domain": "labor",
              "artifact": {
                "path": "example.json",
                "sha256": "$sha",
                "media_type": "application/json"
              },
              "coverage": { "year": null, "effective_from": null, "effective_to": null, "jurisdictions": null },
              "source": { "kind": "INTERNAL", "id": "SRC_LABOR_EXAMPLE", "revision_date": "2025-12-14", "checksum_sha256": null },
              "approvals": [ { "role": "ENGINEERING", "reference": "unit-test", "approved_at": "2025-12-14", "name": null } ]
            }
        """.trimIndent()

        Files.writeString(dir.resolve("example.json.metadata.json"), meta)

        val errors = ContentMetadataValidation.validateSidecar(mapper, artifact, expectedDomain = "tax")
        assertEquals(true, errors.any { it.message.contains("domain must be") })
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}

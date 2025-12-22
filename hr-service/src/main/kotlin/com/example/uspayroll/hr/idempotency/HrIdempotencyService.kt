package com.example.uspayroll.hr.idempotency

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest

@Service
class HrIdempotencyService(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {

    data class StoredResponse(
        val status: Int,
        val bodyJson: String?,
    )

    fun execute(employerId: String, operation: String, idempotencyKey: String?, requestBody: Any, handler: () -> ResponseEntity<Any>): ResponseEntity<Any> {
        if (idempotencyKey.isNullOrBlank()) {
            return handler()
        }

        val requestJson = objectMapper.writeValueAsString(requestBody)
        val requestHash = sha256Hex(requestJson)

        val existing = find(employerId, operation, idempotencyKey)
        if (existing != null) {
            if (existing.requestSha256 != requestHash) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSE")
            }

            val body: JsonNode? = existing.responseJson?.let { objectMapper.readTree(it) }
            return ResponseEntity.status(existing.responseStatus).body(body)
        }

        val resp = handler()
        val responseJson = resp.body?.let { objectMapper.writeValueAsString(it) }

        jdbcTemplate.update(
            """
            INSERT INTO hr_idempotency_record (
                employer_id,
                operation,
                idempotency_key,
                request_sha256,
                response_status,
                response_json
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            employerId,
            operation,
            idempotencyKey,
            requestHash,
            resp.statusCode.value(),
            responseJson,
        )

        return resp
    }

    private data class Record(
        val requestSha256: String,
        val responseStatus: Int,
        val responseJson: String?,
    )

    private fun find(employerId: String, operation: String, idempotencyKey: String): Record? {
        val rows = jdbcTemplate.query(
            """
            SELECT request_sha256, response_status, response_json
            FROM hr_idempotency_record
            WHERE employer_id = ? AND operation = ? AND idempotency_key = ?
            """.trimIndent(),
            { rs, _ ->
                Record(
                    requestSha256 = rs.getString("request_sha256"),
                    responseStatus = rs.getInt("response_status"),
                    responseJson = rs.getString("response_json"),
                )
            },
            employerId,
            operation,
            idempotencyKey,
        )

        return rows.firstOrNull()
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}

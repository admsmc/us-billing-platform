package com.example.usbilling.document.repository

import com.example.usbilling.document.domain.Document
import com.example.usbilling.document.domain.DocumentMetadata
import com.example.usbilling.document.domain.DocumentType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class DocumentRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun save(document: Document): Document {
        val sql = """
            INSERT INTO document (
                document_id, utility_id, customer_id, account_id, document_type,
                document_name, content_type, storage_key, file_size_bytes,
                uploaded_by, uploaded_at, retention_until, deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        jdbcTemplate.update(
            sql,
            document.documentId,
            document.utilityId,
            document.customerId,
            document.accountId,
            document.documentType.name,
            document.documentName,
            document.contentType,
            document.storageKey,
            document.fileSizeBytes,
            document.uploadedBy,
            document.uploadedAt,
            document.retentionUntil,
            document.deleted,
        )

        return document
    }

    fun findById(documentId: String): Document? {
        val sql = """
            SELECT document_id, utility_id, customer_id, account_id, document_type,
                   document_name, content_type, storage_key, file_size_bytes,
                   uploaded_by, uploaded_at, retention_until, deleted, deleted_at, deleted_by
            FROM document
            WHERE document_id = ?
        """.trimIndent()

        return jdbcTemplate.query(sql, { rs, _ ->
            Document(
                documentId = rs.getString("document_id"),
                utilityId = rs.getString("utility_id"),
                customerId = rs.getString("customer_id"),
                accountId = rs.getString("account_id"),
                documentType = DocumentType.valueOf(rs.getString("document_type")),
                documentName = rs.getString("document_name"),
                contentType = rs.getString("content_type"),
                storageKey = rs.getString("storage_key"),
                fileSizeBytes = rs.getLong("file_size_bytes"),
                uploadedBy = rs.getString("uploaded_by"),
                uploadedAt = rs.getTimestamp("uploaded_at").toLocalDateTime(),
                retentionUntil = rs.getDate("retention_until")?.toLocalDate(),
                deleted = rs.getBoolean("deleted"),
                deletedAt = rs.getTimestamp("deleted_at")?.toLocalDateTime(),
                deletedBy = rs.getString("deleted_by"),
            )
        }, documentId).firstOrNull()
    }

    fun findByCustomer(customerId: String, includeDeleted: Boolean = false): List<DocumentMetadata> {
        val sql = if (includeDeleted) {
            """
            SELECT document_id, document_name, document_type, content_type,
                   file_size_bytes, uploaded_at, uploaded_by
            FROM document
            WHERE customer_id = ?
            ORDER BY uploaded_at DESC
            """.trimIndent()
        } else {
            """
            SELECT document_id, document_name, document_type, content_type,
                   file_size_bytes, uploaded_at, uploaded_by
            FROM document
            WHERE customer_id = ? AND deleted = FALSE
            ORDER BY uploaded_at DESC
            """.trimIndent()
        }

        return jdbcTemplate.query(sql, { rs, _ ->
            DocumentMetadata(
                documentId = rs.getString("document_id"),
                documentName = rs.getString("document_name"),
                documentType = DocumentType.valueOf(rs.getString("document_type")),
                contentType = rs.getString("content_type"),
                fileSizeBytes = rs.getLong("file_size_bytes"),
                uploadedAt = rs.getTimestamp("uploaded_at").toLocalDateTime(),
                uploadedBy = rs.getString("uploaded_by"),
            )
        }, customerId)
    }

    fun markAsDeleted(documentId: String, deletedBy: String): Boolean {
        val sql = """
            UPDATE document
            SET deleted = TRUE,
                deleted_at = ?,
                deleted_by = ?
            WHERE document_id = ? AND deleted = FALSE
        """.trimIndent()

        val rowsUpdated = jdbcTemplate.update(
            sql,
            LocalDateTime.now(),
            deletedBy,
            documentId,
        )

        return rowsUpdated > 0
    }

    fun physicallyDelete(documentId: String): Boolean {
        val sql = "DELETE FROM document WHERE document_id = ?"
        val rowsDeleted = jdbcTemplate.update(sql, documentId)
        return rowsDeleted > 0
    }
}

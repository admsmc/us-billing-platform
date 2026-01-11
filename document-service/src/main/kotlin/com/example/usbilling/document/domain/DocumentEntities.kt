package com.example.usbilling.document.domain

import java.time.LocalDate
import java.time.LocalDateTime

data class Document(
    val documentId: String,
    val utilityId: String,
    val customerId: String?,
    val accountId: String?,
    val documentType: DocumentType,
    val documentName: String,
    val contentType: String,
    val storageKey: String,
    val fileSizeBytes: Long,
    val uploadedBy: String?,
    val uploadedAt: LocalDateTime,
    val retentionUntil: LocalDate?,
    val deleted: Boolean = false,
    val deletedAt: LocalDateTime? = null,
    val deletedBy: String? = null,
)

data class DocumentVersion(
    val versionId: String,
    val documentId: String,
    val versionNumber: Int,
    val storageKey: String,
    val fileSizeBytes: Long,
    val contentType: String,
    val uploadedBy: String?,
    val uploadedAt: LocalDateTime,
    val versionNotes: String?,
)

enum class DocumentType {
    BILL,
    PROOF_OF_OWNERSHIP,
    LEASE,
    ID,
    MEDICAL_CERTIFICATE,
    VERIFICATION,
    OTHER,
}

data class DocumentMetadata(
    val documentId: String,
    val documentName: String,
    val documentType: DocumentType,
    val contentType: String,
    val fileSizeBytes: Long,
    val uploadedAt: LocalDateTime,
    val uploadedBy: String?,
)

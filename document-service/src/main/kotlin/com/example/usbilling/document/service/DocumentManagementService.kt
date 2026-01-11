package com.example.usbilling.document.service

import com.example.usbilling.document.domain.Document
import com.example.usbilling.document.domain.DocumentMetadata
import com.example.usbilling.document.domain.DocumentType
import com.example.usbilling.document.repository.DocumentRepository
import com.example.usbilling.document.storage.DocumentStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDateTime
import java.util.*

@Service
class DocumentManagementService(
    private val documentRepository: DocumentRepository,
    private val documentStorage: DocumentStorage,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Upload a document and store it.
     */
    fun uploadDocument(
        utilityId: String,
        customerId: String?,
        accountId: String?,
        documentType: DocumentType,
        file: MultipartFile,
        uploadedBy: String?,
    ): Document {
        val documentId = UUID.randomUUID().toString()
        val originalFilename = file.originalFilename ?: "unknown"
        val contentType = file.contentType ?: "application/octet-stream"
        val fileSize = file.size

        logger.info("Uploading document: $documentId, type: $documentType, size: $fileSize bytes")

        // Generate storage key
        val storageKey = generateStorageKey(utilityId, customerId, documentId, originalFilename)

        // Store the file (include content type for downstream consumers/storage backends)
        documentStorage.store(storageKey, file.bytes, contentType)

        // Create document record
        val document = Document(
            documentId = documentId,
            utilityId = utilityId,
            customerId = customerId,
            accountId = accountId,
            documentType = documentType,
            documentName = originalFilename,
            contentType = contentType,
            storageKey = storageKey,
            fileSizeBytes = fileSize,
            uploadedBy = uploadedBy,
            uploadedAt = LocalDateTime.now(),
            retentionUntil = null, // Can be set based on document type policy
            deleted = false,
        )

        return documentRepository.save(document)
    }

    /**
     * Get document metadata by ID.
     */
    fun getDocument(documentId: String): Document? = documentRepository.findById(documentId)

    /**
     * Download document content.
     *
     * Returns the persisted [Document] metadata together with the full file
     * contents as a byte array, or null if the document does not exist or has
     * been soft-deleted.
     */
    fun downloadDocument(documentId: String): Pair<Document, ByteArray>? {
        val document = documentRepository.findById(documentId)

        if (document == null || document.deleted) {
            logger.warn("Document not found or deleted: $documentId")
            return null
        }

        val contentStream = documentStorage.retrieve(document.storageKey)
            ?: run {
                logger.error("Document file not found in storage: ${document.storageKey}")
                return null
            }

        val contentBytes = contentStream.use { it.readBytes() }

        return document to contentBytes
    }

    /**
     * List all documents for a customer.
     */
    fun listDocuments(customerId: String, includeDeleted: Boolean = false): List<DocumentMetadata> =
        documentRepository.findByCustomer(customerId, includeDeleted)

    /**
     * Soft delete a document (mark as deleted but don't remove from storage).
     */
    fun deleteDocument(documentId: String, deletedBy: String): Boolean {
        val success = documentRepository.markAsDeleted(documentId, deletedBy)

        if (success) {
            logger.info("Document soft deleted: $documentId by $deletedBy")
        } else {
            logger.warn("Failed to delete document: $documentId")
        }

        return success
    }

    /**
     * Physically delete a document and its stored file.
     * Should only be used for retention policy enforcement or admin operations.
     */
    fun purgeDocument(documentId: String): Boolean {
        val document = documentRepository.findById(documentId)

        if (document == null) {
            logger.warn("Cannot purge document: not found - $documentId")
            return false
        }

        // Delete from storage
        documentStorage.delete(document.storageKey)

        // Delete from database
        val deleted = documentRepository.physicallyDelete(documentId)

        if (deleted) {
            logger.info("Document purged: $documentId")
        }

        return deleted
    }

    /**
     * Generate a hierarchical storage key for organizing documents.
     */
    private fun generateStorageKey(
        utilityId: String,
        customerId: String?,
        documentId: String,
        filename: String,
    ): String {
        val extension = filename.substringAfterLast('.', "")
        val customerPath = customerId ?: "shared"

        return "documents/$utilityId/$customerPath/$documentId.$extension"
    }
}

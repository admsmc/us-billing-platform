package com.example.usbilling.document.controller

import com.example.usbilling.document.domain.DocumentType
import com.example.usbilling.document.service.DocumentManagementService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

/**
 * Internal document management API for inter-service communication.
 */
@RestController
@RequestMapping("/utilities/{utilityId}/documents")
class DocumentManagementController(
    private val documentManagementService: DocumentManagementService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Upload a document.
     */
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadDocument(
        @PathVariable utilityId: String,
        @RequestParam customerId: String?,
        @RequestParam accountId: String?,
        @RequestParam documentType: String,
        @RequestParam uploadedBy: String?,
        @RequestParam("file") file: MultipartFile,
    ): ResponseEntity<UploadDocumentResponse> {
        logger.info("Uploading document for customer: $customerId, type: $documentType")

        try {
            val docType = DocumentType.valueOf(documentType)

            val document = documentManagementService.uploadDocument(
                utilityId = utilityId,
                customerId = customerId,
                accountId = accountId,
                documentType = docType,
                file = file,
                uploadedBy = uploadedBy,
            )

            return ResponseEntity.ok(
                UploadDocumentResponse(
                    documentId = document.documentId,
                    documentName = document.documentName,
                    documentType = document.documentType.name,
                    contentType = document.contentType,
                    fileSizeBytes = document.fileSizeBytes,
                    uploadedAt = document.uploadedAt.toString(),
                ),
            )
        } catch (e: Exception) {
            logger.error("Failed to upload document", e)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
        }
    }

    /**
     * Get document metadata.
     */
    @GetMapping("/{documentId}")
    fun getDocument(
        @PathVariable utilityId: String,
        @PathVariable documentId: String,
    ): ResponseEntity<DocumentResponse> {
        val document = documentManagementService.getDocument(documentId)

        return if (document != null && !document.deleted) {
            ResponseEntity.ok(
                DocumentResponse(
                    documentId = document.documentId,
                    documentName = document.documentName,
                    documentType = document.documentType.name,
                    contentType = document.contentType,
                    fileSizeBytes = document.fileSizeBytes,
                    uploadedAt = document.uploadedAt.toString(),
                    uploadedBy = document.uploadedBy,
                ),
            )
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Download document content.
     */
    @GetMapping("/{documentId}/download")
    fun downloadDocument(
        @PathVariable utilityId: String,
        @PathVariable documentId: String,
    ): ResponseEntity<ByteArray> {
        val result = documentManagementService.downloadDocument(documentId)

        return if (result != null) {
            val (document, content) = result

            ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${document.documentName}\"")
                .contentType(MediaType.parseMediaType(document.contentType))
                .contentLength(content.size.toLong())
                .body(content)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * List documents for a customer.
     */
    @GetMapping("/customer/{customerId}")
    fun listDocuments(
        @PathVariable utilityId: String,
        @PathVariable customerId: String,
    ): ResponseEntity<ListDocumentsResponse> {
        val documents = documentManagementService.listDocuments(customerId)

        return ResponseEntity.ok(
            ListDocumentsResponse(
                documents = documents.map { doc ->
                    DocumentSummary(
                        documentId = doc.documentId,
                        documentName = doc.documentName,
                        documentType = doc.documentType.name,
                        contentType = doc.contentType,
                        fileSizeBytes = doc.fileSizeBytes,
                        uploadedAt = doc.uploadedAt.toString(),
                    )
                },
            ),
        )
    }

    /**
     * Delete a document (soft delete).
     */
    @DeleteMapping("/{documentId}")
    fun deleteDocument(
        @PathVariable utilityId: String,
        @PathVariable documentId: String,
        @RequestParam deletedBy: String,
    ): ResponseEntity<Void> {
        val success = documentManagementService.deleteDocument(documentId, deletedBy)

        return if (success) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }
}

// Response DTOs

data class UploadDocumentResponse(
    val documentId: String,
    val documentName: String,
    val documentType: String,
    val contentType: String,
    val fileSizeBytes: Long,
    val uploadedAt: String,
)

data class DocumentResponse(
    val documentId: String,
    val documentName: String,
    val documentType: String,
    val contentType: String,
    val fileSizeBytes: Long,
    val uploadedAt: String,
    val uploadedBy: String?,
)

data class DocumentSummary(
    val documentId: String,
    val documentName: String,
    val documentType: String,
    val contentType: String,
    val fileSizeBytes: Long,
    val uploadedAt: String,
)

data class ListDocumentsResponse(
    val documents: List<DocumentSummary>,
)

package com.example.usbilling.portal.controller

import com.example.usbilling.portal.security.CustomerPrincipal
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

@RestController
@RequestMapping("/api/customers/me/documents")
class CustomerDocumentController(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${customer-portal.document-service-url}")
    private val documentServiceUrl: String,
    @Value("\${customer-portal.billing-orchestrator-url}")
    private val billingOrchestratorUrl: String,
) {

    private val documentClient: WebClient by lazy {
        webClientBuilder.baseUrl(documentServiceUrl).build()
    }

    private val billingClient: WebClient by lazy {
        webClientBuilder.baseUrl(billingOrchestratorUrl).build()
    }

    @GetMapping
    fun listAvailableDocuments(
        @AuthenticationPrincipal principal: CustomerPrincipal,
    ): ResponseEntity<Any> {
        // Fetch document list from document-service
        val response = documentClient
            .get()
            .uri("/utilities/{utilityId}/customers/{customerId}/documents", principal.utilityId, principal.customerId)
            .retrieve()
            .bodyToMono<Map<String, Any>>()
            .block()

        return ResponseEntity.ok(response)
    }

    @GetMapping("/{documentId}/download")
    fun downloadDocument(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable documentId: String,
    ): ResponseEntity<ByteArray> {
        // Fetch document metadata first to verify ownership
        val documentMeta = documentClient
            .get()
            .uri("/utilities/{utilityId}/documents/{documentId}/metadata", principal.utilityId, documentId)
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Failed to fetch document metadata: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .block()

        // Verify customer owns this document
        val customerId = documentMeta?.get("customerId") as? String
        if (customerId != principal.customerId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        // Fetch the actual document content
        val documentBytes = documentClient
            .get()
            .uri("/utilities/{utilityId}/documents/{documentId}/content", principal.utilityId, documentId)
            .retrieve()
            .bodyToMono<ByteArray>()
            .block() ?: return ResponseEntity.notFound().build()

        val documentType = documentMeta?.get("documentType") as? String ?: "application/pdf"
        val filename = documentMeta?.get("filename") as? String ?: "document-$documentId.pdf"

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(documentType))
            .header("Content-Disposition", "attachment; filename=\"$filename\"")
            .body(documentBytes)
    }

    @GetMapping("/bills/{billId}/pdf")
    fun downloadBillPdf(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable billId: String,
    ): ResponseEntity<ByteArray> {
        // Fetch bill data first to verify ownership
        val bill = billingClient
            .get()
            .uri("/utilities/{utilityId}/bills/{billId}", principal.utilityId, billId)
            .retrieve()
            .onStatus({ it.is4xxClientError || it.is5xxServerError }) { response ->
                response.bodyToMono<String>().map { body ->
                    RuntimeException("Failed to fetch bill: $body")
                }
            }
            .bodyToMono<Map<String, Any>>()
            .block()

        // Verify customer has access to this bill
        val accountId = bill?.get("accountId") as? String
        if (accountId != null && !principal.hasAccessToAccount(accountId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        // Generate PDF via document-service
        val pdfBytes = documentClient
            .post()
            .uri("/documents/bills/pdf")
            .bodyValue(bill ?: emptyMap<String, Any>())
            .retrieve()
            .bodyToMono<ByteArray>()
            .block() ?: return ResponseEntity.internalServerError().build()

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header("Content-Disposition", "attachment; filename=\"bill-$billId.pdf\"")
            .body(pdfBytes)
    }
}

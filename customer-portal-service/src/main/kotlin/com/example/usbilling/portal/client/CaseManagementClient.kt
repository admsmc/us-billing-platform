package com.example.usbilling.portal.client

import com.example.usbilling.portal.controller.CaseDetailResponse
import com.example.usbilling.portal.controller.CaseNoteResponse
import com.example.usbilling.portal.controller.CaseResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

@Component
class CaseManagementClient(
    private val webClient: WebClient,
    @Value("\${customer-portal.case-management-url:http://localhost:8092}")
    private val caseManagementUrl: String,
) {

    fun createCase(
        utilityId: String,
        accountId: String?,
        customerId: String?,
        caseType: String,
        caseCategory: String,
        title: String,
        description: String?,
        priority: String?,
        openedBy: String,
    ): CaseResponse {
        val request = mapOf(
            "title" to title,
            "description" to description,
            "accountId" to accountId,
            "customerId" to customerId,
            "caseType" to caseType,
            "caseCategory" to caseCategory,
            "priority" to (priority ?: "MEDIUM"),
            "openedBy" to openedBy,
        )

        return webClient.post()
            .uri("$caseManagementUrl/utilities/$utilityId/cases")
            .bodyValue(request)
            .retrieve()
            .bodyToMono<CaseResponse>()
            .block()!!
    }

    fun getCaseById(utilityId: String, caseId: String): CaseResponse = webClient.get()
        .uri("$caseManagementUrl/utilities/$utilityId/cases/$caseId")
        .retrieve()
        .bodyToMono<CaseResponse>()
        .block()!!

    fun getCaseDetail(utilityId: String, caseId: String): CaseDetailResponse = webClient.get()
        .uri("$caseManagementUrl/utilities/$utilityId/cases/$caseId")
        .retrieve()
        .bodyToMono<CaseDetailResponse>()
        .block()!!

    fun getCasesByCustomerId(utilityId: String, customerId: String): List<CaseResponse> {
        // In production, case-management-service would have a customer-specific endpoint
        // For now, we'd filter cases by customerId on the client side or add the endpoint
        return webClient.get()
            .uri("$caseManagementUrl/utilities/$utilityId/cases?customerId=$customerId")
            .retrieve()
            .bodyToMono<List<CaseResponse>>()
            .block() ?: emptyList()
    }

    fun addNote(
        utilityId: String,
        caseId: String,
        noteText: String,
        noteType: String,
        createdBy: String,
        customerVisible: Boolean,
    ): CaseNoteResponse {
        val request = mapOf(
            "noteText" to noteText,
            "noteType" to noteType,
            "createdBy" to createdBy,
            "customerVisible" to customerVisible,
        )

        return webClient.post()
            .uri("$caseManagementUrl/utilities/$utilityId/cases/$caseId/notes")
            .bodyValue(request)
            .retrieve()
            .bodyToMono<CaseNoteResponse>()
            .block()!!
    }
}

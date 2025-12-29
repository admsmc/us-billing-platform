package com.example.usbilling.customer.api

import com.example.usbilling.shared.CustomerId
import java.time.Instant
import java.time.LocalDate

/**
 * Port interfaces for customer service boundaries.
 * These are implemented in customer-service and consumed by other services.
 */

// Query Ports

interface CustomerAccountProvider {
    fun getAccount(accountId: String, asOfDate: LocalDate = LocalDate.now()): CustomerAccountDto?
    fun searchAccounts(criteria: AccountSearchCriteria): List<CustomerAccountDto>
    fun getAccountHistory(accountId: String): List<CustomerAccountDto>
}

interface CustomerProvider {
    fun getCustomer(customerId: CustomerId, asOfDate: LocalDate = LocalDate.now()): CustomerDto?
    fun getCustomersByAccount(accountId: String): List<AccountCustomerRoleDto>
}

interface InteractionHistoryProvider {
    fun getInteractions(accountId: String?, customerId: CustomerId?, limit: Int = 100): List<CustomerInteractionDto>
    fun getInteraction(interactionId: String): CustomerInteractionDto?
}

interface CaseProvider {
    fun getCase(caseId: String): CaseRecordDto?
    fun searchCases(criteria: CaseSearchCriteria): List<CaseRecordDto>
    fun getCasesByAccount(accountId: String): List<CaseRecordDto>
}

// Command Ports

interface AccountLifecycleService {
    fun createAccount(request: CreateAccountRequest, createdBy: String): CustomerAccountDto
    fun activateAccount(accountId: String, activatedBy: String, reason: String?): CustomerAccountDto
    fun suspendAccount(accountId: String, suspendedBy: String, reason: String): CustomerAccountDto
    fun closeAccount(accountId: String, closedBy: String, reason: String): CustomerAccountDto
    fun updateAccount(accountId: String, updates: UpdateAccountRequest, updatedBy: String): CustomerAccountDto
}

interface CustomerManagementService {
    fun createCustomer(request: CreateCustomerRequest, createdBy: String): CustomerDto
    fun updateCustomer(customerId: CustomerId, updates: UpdateCustomerRequest, updatedBy: String): CustomerDto
    fun addCustomerToAccount(request: AddCustomerToAccountRequest, addedBy: String): AccountCustomerRoleDto
    fun removeCustomerFromAccount(relationshipId: String, removedBy: String, effectiveDate: LocalDate)
}

interface InteractionRecordingService {
    fun logInteraction(request: LogInteractionRequest): CustomerInteractionDto
}

interface CaseManagementService {
    fun createCase(request: CreateCaseRequest, createdBy: String): CaseRecordDto
    fun updateCaseStatus(caseId: String, newStatus: String, updatedBy: String, reason: String?, notes: String?): CaseRecordDto
    fun assignCase(caseId: String, assignedTo: String?, assignedTeam: String?, assignedBy: String): CaseRecordDto
    fun addCaseNote(caseId: String, note: AddCaseNoteRequest, createdBy: String): CaseNoteDto
    fun closeCase(caseId: String, closedBy: String, resolutionNotes: String): CaseRecordDto
}

// DTOs

data class CustomerAccountDto(
    val accountId: String,
    val utilityId: String,
    val customerId: String,
    val accountNumber: String,
    val accountType: String,
    val accountStatus: String,
    val holderName: String,
    val holderType: String,
    val identityVerified: Boolean,
    val billingAddress: AddressDto,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate,
    val createdBy: String,
    val modifiedBy: String,
    val versionSequence: Int,
)

data class CustomerDto(
    val customerId: String,
    val utilityId: String,
    val customerType: String,
    val firstName: String?,
    val lastName: String?,
    val businessName: String?,
    val identityVerified: Boolean,
    val preferredLanguage: String,
)

data class AccountCustomerRoleDto(
    val relationshipId: String,
    val accountId: String,
    val customerId: String,
    val roleType: String,
    val isPrimary: Boolean,
    val authorizationLevel: String,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate,
)

data class CustomerInteractionDto(
    val interactionId: String,
    val utilityId: String,
    val accountId: String?,
    val customerId: String?,
    val interactionType: String,
    val interactionChannel: String,
    val interactionReason: String,
    val direction: String,
    val summary: String,
    val outcome: String?,
    val timestamp: Instant,
    val initiatedBy: String?,
)

data class CaseRecordDto(
    val caseId: String,
    val caseNumber: String,
    val utilityId: String,
    val accountId: String?,
    val customerId: String?,
    val caseType: String,
    val caseCategory: String,
    val status: String,
    val priority: String,
    val title: String,
    val description: String,
    val assignedTo: String?,
    val assignedTeam: String?,
    val createdAt: Instant,
    val openedBy: String,
)

data class CaseNoteDto(
    val noteId: String,
    val caseId: String,
    val noteType: String,
    val content: String,
    val isInternal: Boolean,
    val createdBy: String,
    val createdAt: Instant,
)

data class AddressDto(
    val addressLine1: String,
    val addressLine2: String?,
    val city: String,
    val state: String,
    val postalCode: String,
    val country: String = "US",
)

// Request DTOs

data class CreateAccountRequest(
    val utilityId: String,
    val customerId: String,
    val accountNumber: String,
    val accountType: String,
    val holderName: String,
    val holderType: String,
    val billingAddress: AddressDto,
    val effectiveFrom: LocalDate = LocalDate.now(),
)

data class UpdateAccountRequest(
    val billingAddress: AddressDto?,
    val holderName: String?,
    val effectiveFrom: LocalDate = LocalDate.now(),
)

data class CreateCustomerRequest(
    val utilityId: String,
    val customerType: String,
    val firstName: String?,
    val lastName: String?,
    val businessName: String?,
    val dateOfBirth: LocalDate?,
    val preferredLanguage: String = "en",
)

data class UpdateCustomerRequest(
    val firstName: String?,
    val lastName: String?,
    val businessName: String?,
    val preferredLanguage: String?,
)

data class AddCustomerToAccountRequest(
    val accountId: String,
    val customerId: String,
    val roleType: String,
    val isPrimary: Boolean = false,
    val authorizationLevel: String,
    val effectiveFrom: LocalDate = LocalDate.now(),
)

data class LogInteractionRequest(
    val utilityId: String,
    val accountId: String?,
    val customerId: String?,
    val interactionType: String,
    val interactionChannel: String,
    val interactionReason: String,
    val direction: String,
    val summary: String,
    val details: String?,
    val outcome: String?,
    val sentiment: String?,
    val initiatedBy: String?,
)

data class CreateCaseRequest(
    val utilityId: String,
    val accountId: String?,
    val customerId: String?,
    val caseType: String,
    val caseCategory: String,
    val priority: String,
    val title: String,
    val description: String,
)

data class AddCaseNoteRequest(
    val noteType: String,
    val content: String,
    val isInternal: Boolean = false,
)

// Search Criteria

data class AccountSearchCriteria(
    val utilityId: String,
    val customerId: String? = null,
    val accountNumber: String? = null,
    val status: String? = null,
    val limit: Int = 100,
)

data class CaseSearchCriteria(
    val utilityId: String,
    val accountId: String? = null,
    val customerId: String? = null,
    val status: String? = null,
    val priority: String? = null,
    val assignedTo: String? = null,
    val assignedTeam: String? = null,
    val limit: Int = 100,
)

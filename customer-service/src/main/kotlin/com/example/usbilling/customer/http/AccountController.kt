package com.example.usbilling.customer.http

import com.example.usbilling.customer.api.*
import com.example.usbilling.customer.repository.JdbcCustomerAccountRepository
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/utilities/{utilityId}/accounts")
class AccountController(
    private val accountLifecycleService: AccountLifecycleService,
    private val accountRepository: JdbcCustomerAccountRepository,
) {
    
    /**
     * Create a new customer account.
     * POST /api/v1/utilities/{utilityId}/accounts
     */
    @PostMapping
    fun createAccount(
        @PathVariable utilityId: String,
        @RequestBody request: CreateAccountRequest,
        @RequestHeader("X-User-Id", defaultValue = "system") userId: String,
    ): ResponseEntity<CustomerAccountDto> {
        val account = accountLifecycleService.createAccount(request, userId)
        return ResponseEntity.status(HttpStatus.CREATED).body(account)
    }
    
    /**
     * Get account details.
     * GET /api/v1/utilities/{utilityId}/accounts/{accountId}
     */
    @GetMapping("/{accountId}")
    fun getAccount(
        @PathVariable utilityId: String,
        @PathVariable accountId: String,
        @RequestParam(required = false) asOfDate: LocalDate?,
    ): ResponseEntity<CustomerAccountDto> {
        val account = accountRepository.getCurrentVersion(accountId, asOfDate ?: LocalDate.now())
            ?: return ResponseEntity.notFound().build()
        
        return ResponseEntity.ok(toDto(account))
    }
    
    /**
     * Search accounts.
     * GET /api/v1/utilities/{utilityId}/accounts?customerId=...&status=...
     */
    @GetMapping
    fun searchAccounts(
        @PathVariable utilityId: String,
        @RequestParam(required = false) customerId: String?,
        @RequestParam(required = false) accountNumber: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "100") limit: Int,
    ): ResponseEntity<List<CustomerAccountDto>> {
        val accounts = accountRepository.searchAccounts(
            utilityId = UtilityId(utilityId),
            customerId = customerId?.let { CustomerId(it) },
            accountNumber = accountNumber,
            status = status?.let { com.example.usbilling.customer.model.AccountStatus.valueOf(it) },
            limit = limit,
        )
        
        return ResponseEntity.ok(accounts.map { toDto(it) })
    }
    
    /**
     * Get account history (all versions).
     * GET /api/v1/utilities/{utilityId}/accounts/{accountId}/history
     */
    @GetMapping("/{accountId}/history")
    fun getAccountHistory(
        @PathVariable utilityId: String,
        @PathVariable accountId: String,
    ): ResponseEntity<List<CustomerAccountDto>> {
        val versions = accountRepository.getAllCurrentVersions(accountId)
        return ResponseEntity.ok(versions.map { toDto(it) })
    }
    
    /**
     * Update account details.
     * PATCH /api/v1/utilities/{utilityId}/accounts/{accountId}
     */
    @PatchMapping("/{accountId}")
    fun updateAccount(
        @PathVariable utilityId: String,
        @PathVariable accountId: String,
        @RequestBody updates: UpdateAccountRequest,
        @RequestHeader("X-User-Id", defaultValue = "system") userId: String,
    ): ResponseEntity<CustomerAccountDto> {
        val account = accountLifecycleService.updateAccount(accountId, updates, userId)
        return ResponseEntity.ok(account)
    }
    
    /**
     * Activate account.
     * POST /api/v1/utilities/{utilityId}/accounts/{accountId}/activate
     */
    @PostMapping("/{accountId}/activate")
    fun activateAccount(
        @PathVariable utilityId: String,
        @PathVariable accountId: String,
        @RequestBody(required = false) body: Map<String, String>?,
        @RequestHeader("X-User-Id", defaultValue = "system") userId: String,
    ): ResponseEntity<CustomerAccountDto> {
        val reason = body?.get("reason")
        val account = accountLifecycleService.activateAccount(accountId, userId, reason)
        return ResponseEntity.ok(account)
    }
    
    /**
     * Suspend account.
     * POST /api/v1/utilities/{utilityId}/accounts/{accountId}/suspend
     */
    @PostMapping("/{accountId}/suspend")
    fun suspendAccount(
        @PathVariable utilityId: String,
        @PathVariable accountId: String,
        @RequestBody body: Map<String, String>,
        @RequestHeader("X-User-Id", defaultValue = "system") userId: String,
    ): ResponseEntity<CustomerAccountDto> {
        val reason = body["reason"] ?: throw IllegalArgumentException("reason is required")
        val account = accountLifecycleService.suspendAccount(accountId, userId, reason)
        return ResponseEntity.ok(account)
    }
    
    /**
     * Close account.
     * POST /api/v1/utilities/{utilityId}/accounts/{accountId}/close
     */
    @PostMapping("/{accountId}/close")
    fun closeAccount(
        @PathVariable utilityId: String,
        @PathVariable accountId: String,
        @RequestBody body: Map<String, String>,
        @RequestHeader("X-User-Id", defaultValue = "system") userId: String,
    ): ResponseEntity<CustomerAccountDto> {
        val reason = body["reason"] ?: throw IllegalArgumentException("reason is required")
        val account = accountLifecycleService.closeAccount(accountId, userId, reason)
        return ResponseEntity.ok(account)
    }
    
    private fun toDto(account: com.example.usbilling.customer.model.CustomerAccount): CustomerAccountDto {
        return CustomerAccountDto(
            accountId = account.accountId,
            utilityId = account.utilityId.value,
            customerId = account.customerId.value,
            accountNumber = account.accountNumber,
            accountType = account.accountType.name,
            accountStatus = account.accountStatus.name,
            holderName = account.holder.holderName,
            holderType = account.holder.holderType.name,
            identityVerified = account.holder.identityVerified,
            billingAddress = AddressDto(
                addressLine1 = account.billingAddress.addressLine1,
                addressLine2 = account.billingAddress.addressLine2,
                city = account.billingAddress.city,
                state = account.billingAddress.state,
                postalCode = account.billingAddress.postalCode,
                country = account.billingAddress.country,
            ),
            effectiveFrom = account.effectiveFrom,
            effectiveTo = account.effectiveTo,
            createdBy = account.createdBy,
            modifiedBy = account.modifiedBy,
            versionSequence = account.versionSequence,
        )
    }
}

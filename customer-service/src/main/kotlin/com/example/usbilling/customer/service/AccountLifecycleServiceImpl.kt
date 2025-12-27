package com.example.usbilling.customer.service

import com.example.usbilling.customer.api.*
import com.example.usbilling.customer.model.*
import com.example.usbilling.customer.repository.JdbcCustomerAccountRepository
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
class AccountLifecycleServiceImpl(
    private val accountRepository: JdbcCustomerAccountRepository,
) : AccountLifecycleService {
    
    @Transactional
    override fun createAccount(request: CreateAccountRequest, createdBy: String): CustomerAccountDto {
        // Validate request
        require(request.accountNumber.isNotBlank()) { "Account number is required" }
        require(request.holderName.isNotBlank()) { "Holder name is required" }
        
        val accountId = "acct-${UUID.randomUUID()}"
        val now = Instant.now()
        
        val account = CustomerAccount(
            accountId = accountId,
            utilityId = UtilityId(request.utilityId),
            customerId = CustomerId(request.customerId),
            accountNumber = request.accountNumber,
            accountType = AccountType.valueOf(request.accountType),
            accountStatus = AccountStatus.PENDING_ACTIVATION,
            holder = AccountHolder(
                holderName = request.holderName,
                holderType = HolderType.valueOf(request.holderType),
                identityVerified = false,
                taxId = null,
                businessName = null,
            ),
            serviceAddress = null,
            billingAddress = BillingAddress(
                addressLine1 = request.billingAddress.addressLine1,
                addressLine2 = request.billingAddress.addressLine2,
                city = request.billingAddress.city,
                state = request.billingAddress.state,
                postalCode = request.billingAddress.postalCode,
                country = request.billingAddress.country,
            ),
            contactMethods = emptyList(),
            effectiveFrom = request.effectiveFrom,
            effectiveTo = LocalDate.MAX,
            systemFrom = now,
            systemTo = Instant.MAX,
            createdBy = createdBy,
            modifiedBy = createdBy,
            versionSequence = 1,
            changeReason = null,
        )
        
        val created = accountRepository.create(account, createdBy, now)
        return toDto(created)
    }
    
    @Transactional
    override fun activateAccount(accountId: String, activatedBy: String, reason: String?): CustomerAccountDto {
        val current = accountRepository.getCurrentVersion(accountId, LocalDate.now())
            ?: throw IllegalArgumentException("Account $accountId not found")
        
        // Validate state transition
        require(current.accountStatus == AccountStatus.PENDING_ACTIVATION || current.accountStatus == AccountStatus.SUSPENDED) {
            "Cannot activate account in status ${current.accountStatus}"
        }
        
        val updated = current.copy(
            accountStatus = AccountStatus.ACTIVE,
            effectiveFrom = LocalDate.now(),
        )
        
        val result = accountRepository.supersede(
            accountId = accountId,
            newVersion = updated,
            modifiedBy = activatedBy,
            changeReason = ChangeReason.CUSTOMER_REQUEST,
            now = Instant.now(),
        )
        
        return toDto(result)
    }
    
    @Transactional
    override fun suspendAccount(accountId: String, suspendedBy: String, reason: String): CustomerAccountDto {
        val current = accountRepository.getCurrentVersion(accountId, LocalDate.now())
            ?: throw IllegalArgumentException("Account $accountId not found")
        
        // Validate state transition
        require(current.accountStatus == AccountStatus.ACTIVE) {
            "Cannot suspend account in status ${current.accountStatus}"
        }
        
        // Business rule: Check for pending payments (would be implemented with payment service integration)
        // For now, just proceed with suspension
        
        val updated = current.copy(
            accountStatus = AccountStatus.SUSPENDED,
            effectiveFrom = LocalDate.now(),
        )
        
        val result = accountRepository.supersede(
            accountId = accountId,
            newVersion = updated,
            modifiedBy = suspendedBy,
            changeReason = ChangeReason.CUSTOMER_REQUEST,
            now = Instant.now(),
        )
        
        return toDto(result)
    }
    
    @Transactional
    override fun closeAccount(accountId: String, closedBy: String, reason: String): CustomerAccountDto {
        val current = accountRepository.getCurrentVersion(accountId, LocalDate.now())
            ?: throw IllegalArgumentException("Account $accountId not found")
        
        // Business rule: Check for active service connections (would query service connection repository)
        // Business rule: Check for outstanding balance (would integrate with billing service)
        // For now, just proceed with closure
        
        val updated = current.copy(
            accountStatus = AccountStatus.CLOSED,
            effectiveFrom = LocalDate.now(),
        )
        
        val result = accountRepository.supersede(
            accountId = accountId,
            newVersion = updated,
            modifiedBy = closedBy,
            changeReason = ChangeReason.CUSTOMER_REQUEST,
            now = Instant.now(),
        )
        
        return toDto(result)
    }
    
    @Transactional
    override fun updateAccount(accountId: String, updates: UpdateAccountRequest, updatedBy: String): CustomerAccountDto {
        val current = accountRepository.getCurrentVersion(accountId, updates.effectiveFrom)
            ?: throw IllegalArgumentException("Account $accountId not found")
        
        val updated = current.copy(
            holder = if (updates.holderName != null) {
                current.holder.copy(holderName = updates.holderName)
            } else {
                current.holder
            },
            billingAddress = updates.billingAddress?.let {
                BillingAddress(
                    addressLine1 = it.addressLine1,
                    addressLine2 = it.addressLine2,
                    city = it.city,
                    state = it.state,
                    postalCode = it.postalCode,
                    country = it.country,
                )
            } ?: current.billingAddress,
            effectiveFrom = updates.effectiveFrom,
        )
        
        val result = accountRepository.supersede(
            accountId = accountId,
            newVersion = updated,
            modifiedBy = updatedBy,
            changeReason = ChangeReason.CUSTOMER_REQUEST,
            now = Instant.now(),
        )
        
        return toDto(result)
    }
    
    private fun toDto(account: CustomerAccount): CustomerAccountDto {
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

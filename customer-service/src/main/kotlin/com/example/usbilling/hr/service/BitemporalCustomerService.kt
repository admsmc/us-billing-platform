package com.example.usbilling.hr.service

import com.example.usbilling.billing.model.*
import com.example.usbilling.hr.api.BillingPeriodProvider
import com.example.usbilling.hr.api.CustomerSnapshotProvider
import com.example.usbilling.hr.domain.*
import com.example.usbilling.hr.repository.*
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.*

/**
 * Bitemporal customer service implementing SCD2 append-only pattern.
 * 
 * All operations are append-only:
 * - Create: INSERT new version with open-ended validity
 * - Update: Close current system version + INSERT new version
 * - Delete: Close current system version (logical delete)
 * - Query: Respect both effective and system time dimensions
 * 
 * This service implements the same port interfaces as CustomerService but uses
 * bitemporal tables for complete history tracking.
 */
@Service
@Transactional
class BitemporalCustomerService(
    private val customerAccountRepository: CustomerAccountBitemporalRepository,
    private val servicePointRepository: ServicePointBitemporalRepository,
    private val meterRepository: MeterBitemporalRepository,
    private val billingPeriodRepository: BillingPeriodBitemporalRepository
) : CustomerSnapshotProvider, BillingPeriodProvider {

    /**
     * Get customer snapshot as of a specific date.
     * Implements CustomerSnapshotProvider port interface.
     */
    override fun getCustomerSnapshot(
        utilityId: UtilityId,
        customerId: CustomerId,
        asOfDate: LocalDate
    ): CustomerSnapshot? {
        val customerAccount = customerAccountRepository.findCurrentVersion(
            customerId.value,
            asOfDate
        ) ?: return null
        
        // Verify utility ID matches
        if (customerAccount.utilityId != utilityId.value) return null
        
        // Get active meters for this account as of date
        val meters = meterRepository.findByAccountId(customerId.value, asOfDate)
        
        // Map to MeterInfo domain objects
        val meterInfos = meters.map { meterEntity ->
            MeterInfo(
                meterId = meterEntity.meterId,
                serviceType = parseServiceTypeFromMeterType(meterEntity.meterType),
                meterType = MeterType.AMI,
                installDate = meterEntity.installDate ?: meterEntity.effectiveFrom,
                lastReadDate = meterEntity.lastReadDate
            )
        }
        
        // Build service address from billing address fields
        val serviceAddress = ServiceAddress(
            street1 = customerAccount.billingAddressLine1,
            street2 = customerAccount.billingAddressLine2,
            city = customerAccount.billingCity,
            state = customerAccount.billingState,
            zipCode = customerAccount.billingPostalCode
        )
        
        return CustomerSnapshot(
            customerId = customerId,
            utilityId = utilityId,
            serviceAddress = serviceAddress,
            billingAddress = null,
            customerClass = parseCustomerClass(customerAccount.accountType),
            accountStatus = parseAccountStatus(customerAccount.accountStatus),
            meters = meterInfos,
            specialRates = emptyList()
        )
    }

    /**
     * Get billing period details.
     * Implements BillingPeriodProvider port interface.
     */
    override fun getBillingPeriod(utilityId: UtilityId, billingPeriodId: String): BillingPeriod? {
        val periodEntity = billingPeriodRepository.findCurrentVersion(billingPeriodId) ?: return null
        
        // Verify the customer belongs to this utility
        val customerAccount = customerAccountRepository.findCurrentVersion(periodEntity.accountId) ?: return null
        if (customerAccount.utilityId != utilityId.value) return null
        
        return BillingPeriod(
            id = periodEntity.periodId,
            utilityId = utilityId,
            startDate = periodEntity.startDate,
            endDate = periodEntity.endDate,
            billDate = periodEntity.endDate.plusDays(1),
            dueDate = periodEntity.dueDate,
            frequency = BillingFrequency.MONTHLY
        )
    }

    override fun findBillingPeriodByBillDate(utilityId: UtilityId, billDate: LocalDate): BillingPeriod? {
        // TODO: Implement bill date lookup
        return null
    }

    // ========== SCD2 Append-Only Operations ==========

    /**
     * Create a new customer account - append-only operation.
     * Inserts a new version with open-ended validity.
     */
    fun createCustomerAccount(
        utilityId: String,
        accountNumber: String,
        customerName: String,
        serviceAddress: String,
        customerClass: String?
    ): CustomerAccountEffective {
        val accountId = UUID.randomUUID().toString()
        
        val entity = CustomerAccountEffective.create(
            accountId = accountId,
            utilityId = utilityId,
            accountNumber = accountNumber,
            holderName = customerName,
            billingAddress = serviceAddress,
            accountType = customerClass ?: "RESIDENTIAL",
            createdBy = "api"
        )
        
        return customerAccountRepository.save(entity)
    }

    /**
     * Update customer account - append-only operation.
     * 
     * SCD2 pattern:
     * 1. Close current system version (set system_to = now)
     * 2. Insert new version with updated data
     */
    fun updateCustomerAccount(
        accountId: String,
        updates: CustomerAccountUpdate
    ): CustomerAccountEffective {
        // 1. Get current version
        val current = customerAccountRepository.findCurrentVersion(accountId)
            ?: throw IllegalArgumentException("Account $accountId not found")
        
        // 2. Close current system version
        customerAccountRepository.closeCurrentSystemVersion(accountId)
        
        // 3. Parse address if provided
        val addressParts = updates.serviceAddress?.split("|")?.map { it.trim() }
        
        // 4. Create new version with updates
        val newVersion = current.copy(
            holderName = updates.customerName ?: current.holderName,
            accountType = updates.customerClass ?: current.accountType,
            accountStatus = if (updates.active == false) "CLOSED" else current.accountStatus,
            billingAddressLine1 = addressParts?.getOrNull(0) ?: current.billingAddressLine1,
            billingAddressLine2 = addressParts?.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: current.billingAddressLine2,
            billingCity = addressParts?.getOrNull(2) ?: current.billingCity,
            billingState = addressParts?.getOrNull(3) ?: current.billingState,
            billingPostalCode = addressParts?.getOrNull(4) ?: current.billingPostalCode,
            systemFrom = java.time.Instant.now(),
            systemTo = java.time.Instant.parse("9999-12-31T23:59:59Z"),
            modifiedBy = "api",
            versionSequence = current.versionSequence + 1,
            changeReason = "Updated via API"
        ).apply { isNewEntity = true }
        
        return customerAccountRepository.save(newVersion)
    }

    /**
     * Create a service point for an account.
     */
    fun createServicePoint(
        accountId: String,
        serviceAddress: String,
        serviceType: String
    ): ServicePointEffective {
        val servicePointId = UUID.randomUUID().toString()
        val addressId = UUID.randomUUID().toString() // Create a dummy address ID
        
        // Get customer to determine utilityId
        val customer = customerAccountRepository.findCurrentVersion(accountId)
            ?: throw IllegalArgumentException("Account $accountId not found")
        
        val entity = ServicePointEffective.create(
            servicePointId = servicePointId,
            utilityId = customer.utilityId,
            accountId = accountId,
            addressId = addressId,
            serviceType = serviceType,
            createdBy = "api"
        )
        
        return servicePointRepository.save(entity)
    }

    /**
     * Create a meter for a service point.
     */
    fun createMeter(
        servicePointId: String,
        utilityServiceType: String,
        meterNumber: String
    ): MeterEffective {
        val meterId = UUID.randomUUID().toString()
        
        // Map service type to meter type
        val meterType = when (utilityServiceType.uppercase()) {
            "ELECTRIC" -> "ELECTRIC_SMART"
            "GAS" -> "GAS_ANALOG"
            "WATER" -> "WATER_ANALOG"
            else -> "ELECTRIC_SMART"
        }
        
        val entity = MeterEffective.create(
            meterId = meterId,
            servicePointId = servicePointId,
            meterSerial = meterNumber,
            meterType = meterType,
            createdBy = "api"
        )
        
        return meterRepository.save(entity)
    }

    /**
     * Create a billing period for an account.
     */
    fun createBillingPeriod(
        accountId: String,
        startDate: LocalDate,
        endDate: LocalDate,
        dueDate: LocalDate,
        status: String = "OPEN"
    ): BillingPeriodEffective {
        val periodId = UUID.randomUUID().toString()
        
        val entity = BillingPeriodEffective.create(
            periodId = periodId,
            accountId = accountId,
            startDate = startDate,
            endDate = endDate,
            dueDate = dueDate,
            status = status
        )
        
        return billingPeriodRepository.save(entity)
    }

    /**
     * Update billing period status - append-only.
     */
    fun updateBillingPeriodStatus(
        periodId: String,
        newStatus: String
    ): BillingPeriodEffective {
        val current = billingPeriodRepository.findCurrentVersion(periodId)
            ?: throw IllegalArgumentException("Period $periodId not found")
        
        billingPeriodRepository.closeCurrentSystemVersion(periodId)
        
        val newVersion = current.copy(
            status = newStatus,
            systemFrom = java.time.Instant.now(),
            systemTo = java.time.Instant.parse("9999-12-31T23:59:59Z")
        ).apply { isNewEntity = true }
        
        return billingPeriodRepository.save(newVersion)
    }

    /**
     * Get customer account as of specific date.
     */
    fun getCustomerAccountAsOf(accountId: String, asOfDate: LocalDate): CustomerAccountEffective? {
        return customerAccountRepository.findCurrentVersion(accountId, asOfDate)
    }

    /**
     * Get complete history of a customer account.
     */
    fun getCustomerAccountHistory(accountId: String): List<CustomerAccountEffective> {
        return customerAccountRepository.findAllVersions(accountId)
    }

    /**
     * List all customers for a utility (current versions only).
     */
    fun listCustomersByUtility(utilityId: String): List<CustomerAccountEffective> {
        return customerAccountRepository.findByUtilityId(utilityId)
    }

    /**
     * Get meters for an account.
     */
    fun getMetersByAccount(accountId: String, asOfDate: LocalDate = LocalDate.now()): List<MeterEffective> {
        return meterRepository.findByAccountId(accountId, asOfDate)
    }

    /**
     * Get billing periods for an account.
     */
    fun getBillingPeriodsByAccount(accountId: String, asOfDate: LocalDate = LocalDate.now()): List<BillingPeriodEffective> {
        return billingPeriodRepository.findByAccountId(accountId, asOfDate)
    }

    // ========== Helper functions ==========

    private fun parseServiceType(value: String): ServiceType {
        return try {
            ServiceType.valueOf(value.uppercase())
        } catch (e: IllegalArgumentException) {
            ServiceType.ELECTRIC
        }
    }
    
    private fun parseServiceTypeFromMeterType(meterType: String): ServiceType {
        return when {
            meterType.startsWith("ELECTRIC", ignoreCase = true) -> ServiceType.ELECTRIC
            meterType.startsWith("GAS", ignoreCase = true) -> ServiceType.GAS
            meterType.startsWith("WATER", ignoreCase = true) -> ServiceType.WATER
            else -> ServiceType.ELECTRIC
        }
    }

    private fun parseCustomerClass(value: String?): CustomerClass {
        if (value == null) return CustomerClass.RESIDENTIAL
        return try {
            CustomerClass.valueOf(value.uppercase())
        } catch (e: IllegalArgumentException) {
            CustomerClass.RESIDENTIAL
        }
    }
    
    private fun parseAccountStatus(value: String): AccountStatus {
        return when (value.uppercase()) {
            "ACTIVE" -> AccountStatus.ACTIVE
            "CLOSED", "SUSPENDED" -> AccountStatus.CLOSED
            "PENDING_ACTIVATION" -> AccountStatus.PENDING
            else -> AccountStatus.ACTIVE
        }
    }
    
    private fun parseServiceAddress(addressText: String): ServiceAddress {
        val parts = addressText.split("|").map { it.trim() }
        
        return when (parts.size) {
            5 -> ServiceAddress(
                street1 = parts[0],
                street2 = parts[1].takeIf { it.isNotEmpty() },
                city = parts[2],
                state = parts[3],
                zipCode = parts[4]
            )
            4 -> ServiceAddress(
                street1 = parts[0],
                street2 = null,
                city = parts[1],
                state = parts[2],
                zipCode = parts[3]
            )
            else -> ServiceAddress(
                street1 = addressText,
                street2 = null,
                city = "Unknown",
                state = "MI",
                zipCode = "00000"
            )
        }
    }
}

/**
 * DTO for customer account updates.
 */
data class CustomerAccountUpdate(
    val customerName: String? = null,
    val serviceAddress: String? = null,
    val customerClass: String? = null,
    val active: Boolean? = null
)

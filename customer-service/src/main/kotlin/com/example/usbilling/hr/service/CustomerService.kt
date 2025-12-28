package com.example.usbilling.hr.service

import com.example.usbilling.billing.model.*
import com.example.usbilling.hr.api.BillingPeriodProvider
import com.example.usbilling.hr.api.CustomerSnapshotProvider
import com.example.usbilling.hr.repository.BillingPeriodRepository
import com.example.usbilling.hr.repository.CustomerRepository
import com.example.usbilling.hr.repository.MeterReadRepository
import com.example.usbilling.hr.repository.MeterRepository
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId

/**
 * Customer service - implements CustomerSnapshotProvider and BillingPeriodProvider ports.
 *
 * Assembles CustomerSnapshot and BillingPeriod domain objects from database entities.
 */
@Service
class CustomerService(
    private val customerRepository: CustomerRepository,
    private val meterRepository: MeterRepository,
    private val billingPeriodRepository: BillingPeriodRepository,
    private val meterReadRepository: MeterReadRepository
) : CustomerSnapshotProvider, BillingPeriodProvider {

    override fun getCustomerSnapshot(
        utilityId: UtilityId,
        customerId: CustomerId,
        asOfDate: LocalDate
    ): CustomerSnapshot? {
        val customerEntity = customerRepository.findById(customerId.value).orElse(null) ?: return null

        // Verify utility ID matches
        if (customerEntity.utilityId != utilityId.value) return null

        // Get active meters for this customer
        val meters = meterRepository.findActiveByCustomerId(customerId.value)

        // Map to MeterInfo domain objects
        val meterInfos = meters.map { meterEntity ->
            val lastRead = meterReadRepository.findLatestByMeterId(meterEntity.meterId)
            MeterInfo(
                meterId = meterEntity.meterId,
                serviceType = parseServiceType(meterEntity.utilityServiceType),
                meterType = MeterType.AMI, // Default to AMI for now
                installDate = meterEntity.installDate,
                lastReadDate = lastRead?.readDate
            )
        }

        // Parse address from service_address text field
        val serviceAddress = parseServiceAddress(customerEntity.serviceAddress)

        return CustomerSnapshot(
            customerId = customerId,
            utilityId = utilityId,
            serviceAddress = serviceAddress,
            billingAddress = null, // Could add separate billing_address column if needed
            customerClass = parseCustomerClass(customerEntity.customerClass),
            accountStatus = if (customerEntity.active) AccountStatus.ACTIVE else AccountStatus.CLOSED,
            meters = meterInfos,
            specialRates = emptyList() // Future: load from special_rates table
        )
    }

    override fun getBillingPeriod(utilityId: UtilityId, billingPeriodId: String): BillingPeriod? {
        val periodEntity = billingPeriodRepository.findById(billingPeriodId).orElse(null) ?: return null

        // Verify the customer belongs to this utility
        val customerEntity = customerRepository.findById(periodEntity.customerId).orElse(null) ?: return null
        if (customerEntity.utilityId != utilityId.value) return null

        return BillingPeriod(
            id = periodEntity.periodId,
            utilityId = utilityId,
            startDate = periodEntity.startDate,
            endDate = periodEntity.endDate,
            billDate = periodEntity.endDate.plusDays(1), // Default: bill next day after period ends
            dueDate = periodEntity.endDate.plusDays(21), // Default: due 20 days after bill date
            frequency = BillingFrequency.MONTHLY // Default for now
        )
    }

    override fun findBillingPeriodByBillDate(utilityId: UtilityId, billDate: LocalDate): BillingPeriod? {
        // Simple implementation: find periods where billDate falls within [startDate, endDate]
        // In practice, you'd have a dedicated query or bill_date column
        
        // For now, return null (would need dedicated query or index)
        // TODO: Add query findByBillDate if this is needed frequently
        return null
    }

    /**
     * Get meter reads for a billing period.
     * Note: This is NOT part of the port interface, but useful for HTTP API.
     */
    fun getMeterReads(billingPeriodId: String): List<MeterRead> {
        val periodEntity = billingPeriodRepository.findById(billingPeriodId).orElse(null) ?: return emptyList()
        
        // Get all meter reads for this period
        val meterReadEntities = meterReadRepository.findByBillingPeriodId(billingPeriodId)
        
        return meterReadEntities.map { readEntity ->
            val meterEntity = meterRepository.findById(readEntity.meterId).orElseThrow()
            MeterRead(
                meterId = readEntity.meterId,
                serviceType = parseServiceType(meterEntity.utilityServiceType),
                readingValue = readEntity.readingValue.toDouble(),
                readDate = readEntity.readDate,
                usageUnit = inferUsageUnit(parseServiceType(meterEntity.utilityServiceType)),
                multiplier = 1.0,
                readingType = parseReadingType(readEntity.readingType)
            )
        }
    }

    // Helper functions to parse enums and types

    private fun parseServiceType(value: String): ServiceType {
        return try {
            ServiceType.valueOf(value.uppercase())
        } catch (e: IllegalArgumentException) {
            ServiceType.ELECTRIC // Default fallback
        }
    }

    private fun parseCustomerClass(value: String?): CustomerClass {
        if (value == null) return CustomerClass.RESIDENTIAL
        return try {
            CustomerClass.valueOf(value.uppercase())
        } catch (e: IllegalArgumentException) {
            CustomerClass.RESIDENTIAL // Default fallback
        }
    }

    private fun parseReadingType(value: String): ReadingType {
        return try {
            ReadingType.valueOf(value.uppercase())
        } catch (e: IllegalArgumentException) {
            ReadingType.ACTUAL // Default fallback
        }
    }

    private fun inferUsageUnit(serviceType: ServiceType): UsageUnit {
        return when (serviceType) {
            ServiceType.ELECTRIC -> UsageUnit.KWH
            ServiceType.GAS -> UsageUnit.THERMS
            ServiceType.WATER -> UsageUnit.CCF
            ServiceType.WASTEWATER -> UsageUnit.CCF
            ServiceType.BROADBAND -> UsageUnit.MBPS
            ServiceType.REFUSE -> UsageUnit.CONTAINERS
            ServiceType.RECYCLING -> UsageUnit.CONTAINERS
            ServiceType.STORMWATER -> UsageUnit.NONE
            ServiceType.DONATION -> UsageUnit.NONE
        }
    }

    /**
     * Parse service address from text field.
     * Format expected: "street1 [| street2] | city | state | zipCode"
     * Example: "123 Main St | Apt 4B | Detroit | MI | 48201"
     */
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
            else -> {
                // Fallback: treat entire string as street1
                ServiceAddress(
                    street1 = addressText,
                    street2 = null,
                    city = "Unknown",
                    state = "MI",
                    zipCode = "00000"
                )
            }
        }
    }
}

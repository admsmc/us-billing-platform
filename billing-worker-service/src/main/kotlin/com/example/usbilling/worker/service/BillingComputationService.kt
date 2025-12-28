package com.example.usbilling.worker.service

import com.example.usbilling.billing.engine.BillingEngine
import com.example.usbilling.billing.model.*
import com.example.usbilling.shared.*
import com.example.usbilling.worker.client.CustomerServiceClient
import com.example.usbilling.worker.client.RateServiceClient
import com.example.usbilling.worker.client.RegulatoryServiceClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * Billing computation service - orchestrates bill calculation.
 *
 * Coordinates HTTP calls to customer, rate, and regulatory services,
 * then invokes BillingEngine to compute the final bill.
 */
@Service
class BillingComputationService(
    private val customerServiceClient: CustomerServiceClient,
    private val rateServiceClient: RateServiceClient,
    private val regulatoryServiceClient: RegulatoryServiceClient
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Compute a bill for a customer and billing period.
     *
     * @param utilityId The utility company
     * @param customerId The customer
     * @param billingPeriodId The billing period identifier
     * @param serviceState State where service is provided (for rate/regulatory context)
     * @return Computed bill result, or null if prerequisites cannot be fetched
     */
    fun computeBill(
        utilityId: UtilityId,
        customerId: CustomerId,
        billingPeriodId: String,
        serviceState: String
    ): BillResult? {
        logger.info("Computing bill for customer=${customerId.value}, period=$billingPeriodId")

        // 1. Fetch customer snapshot
        val customerSnapshot = customerServiceClient.getCustomerSnapshot(
            utilityId, customerId, LocalDate.now()
        )
        if (customerSnapshot == null) {
            logger.error("Failed to fetch customer snapshot for customer=${customerId.value}")
            return null
        }

        // 2. Fetch billing period with meter reads
        val periodWithReads = customerServiceClient.getBillingPeriod(
            utilityId, customerId, billingPeriodId
        )
        if (periodWithReads == null) {
            logger.error("Failed to fetch billing period $billingPeriodId")
            return null
        }

        // 3. Fetch rate context
        val rateContext = rateServiceClient.getRateContext(
            utilityId, serviceState, periodWithReads.period.billDate
        )
        if (rateContext == null) {
            logger.error("Failed to fetch rate context for state=$serviceState")
            return null
        }

        // 4. Fetch regulatory context
        val regulatoryContext = regulatoryServiceClient.getRegulatoryContext(
            utilityId, serviceState, periodWithReads.period.billDate
        )
        if (regulatoryContext == null) {
            logger.error("Failed to fetch regulatory context for state=$serviceState")
            return null
        }

        // 5. Group meter reads by service type
        val readsByService = periodWithReads.meterReads.groupBy { it.serviceType }

        // 6. Validate service integration (demonstrates full service connectivity)
        logger.info("Bill computation successful - fetched all required contexts")
        logger.info("Customer: ${customerSnapshot.customerId.value} at ${customerSnapshot.serviceAddress}")
        logger.info("Services found: ${readsByService.keys}")
        logger.info("Rate schedules: ${rateContext.rateSchedules.keys}")
        logger.info("Regulatory charges: ${regulatoryContext.regulatoryCharges.size}")
        
        // TODO: Build BillInput object and call BillingEngine.calculateBill(input)
        // This requires creating MeterReadPairs, AccountBalance, etc.
        // Will be completed when billing-orchestrator-service provides bill persistence
        
        return null // Demonstrates service connectivity working
    }

    /**
     * Compute a bill for a single service with provided usage.
     * Useful for demo/dry-run scenarios.
     */
    fun computeSingleServiceBill(
        utilityId: UtilityId,
        customerId: CustomerId,
        serviceType: ServiceType,
        usage: Double,
        billingPeriod: BillingPeriod,
        serviceState: String
    ): BillResult? {
        logger.info("Computing single-service bill for customer=${customerId.value}, service=$serviceType")

        // Fetch contexts to demonstrate service integration
        val customerSnapshot = customerServiceClient.getCustomerSnapshot(
            utilityId, customerId, LocalDate.now()
        ) ?: return null

        val rateContext = rateServiceClient.getRateContext(
            utilityId, serviceState, billingPeriod.billDate
        ) ?: return null

        val regulatoryContext = regulatoryServiceClient.getRegulatoryContext(
            utilityId, serviceState, billingPeriod.billDate
        ) ?: return null

        val tariff = rateContext.rateSchedules[serviceType] ?: return null

        logger.info("Successfully fetched all contexts for $serviceType billing")
        logger.info("Tariff type: ${tariff::class.simpleName}")
        logger.info("Regulatory charges: ${regulatoryContext.regulatoryCharges.size}")
        
        // TODO: Build BillInput and call BillingEngine
        return null
    }
}

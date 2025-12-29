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
    private val regulatoryServiceClient: RegulatoryServiceClient,
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
        serviceState: String,
    ): BillResult? {
        logger.info("Computing bill for customer=${customerId.value}, period=$billingPeriodId")

        // 1. Fetch customer snapshot
        val customerSnapshot = customerServiceClient.getCustomerSnapshot(
            utilityId,
            customerId,
            LocalDate.now(),
        )
        if (customerSnapshot == null) {
            logger.error("Failed to fetch customer snapshot for customer=${customerId.value}")
            return null
        }

        // 2. Fetch billing period with meter reads
        val periodWithReads = customerServiceClient.getBillingPeriod(
            utilityId,
            customerId,
            billingPeriodId,
        )
        if (periodWithReads == null) {
            logger.error("Failed to fetch billing period $billingPeriodId")
            return null
        }

        // 3. Fetch rate context
        val rateContext = rateServiceClient.getRateContext(
            utilityId,
            serviceState,
            periodWithReads.period.billDate,
        )
        if (rateContext == null) {
            logger.error("Failed to fetch rate context for state=$serviceState")
            return null
        }

        // 4. Fetch regulatory context
        val regulatoryContext = regulatoryServiceClient.getRegulatoryContext(
            utilityId,
            serviceState,
            periodWithReads.period.billDate,
        )
        if (regulatoryContext == null) {
            logger.error("Failed to fetch regulatory context for state=$serviceState")
            return null
        }

        // 5. Group meter reads by service type
        val readsByService = periodWithReads.meterReads.groupBy { it.serviceType }

        logger.info("Bill computation - fetched all required contexts")
        logger.info("Customer: ${customerSnapshot.customerId.value} at ${customerSnapshot.serviceAddress}")
        logger.info("Services found: ${readsByService.keys}")
        logger.info("Rate schedules: ${rateContext.rateSchedules.keys}")
        logger.info("Regulatory charges: ${regulatoryContext.regulatoryCharges.size}")

        // 6. Build MeterReadPairs from meter reads
        // Group reads by meterId and create pairs (opening/closing reads)
        val meterReadPairs = buildMeterReadPairs(periodWithReads.meterReads)
        if (meterReadPairs.isEmpty()) {
            logger.error("No valid meter read pairs found for billing period $billingPeriodId")
            return null
        }

        // 7. Determine primary service type and tariff
        val primaryServiceType = meterReadPairs.firstOrNull()?.serviceType
            ?: ServiceType.ELECTRIC
        val primaryTariff = rateContext.rateSchedules[primaryServiceType]
        if (primaryTariff == null) {
            logger.error("No tariff found for primary service type $primaryServiceType")
            return null
        }

        // 8. Build BillInput
        val billInput = BillInput(
            billId = BillId("BILL-${customerId.value}-$billingPeriodId"),
            billRunId = BillingCycleId("RUN-${periodWithReads.period.id}"),
            utilityId = utilityId,
            customerId = customerId,
            billPeriod = periodWithReads.period,
            meterReads = meterReadPairs,
            rateTariff = primaryTariff,
            accountBalance = customerSnapshot.accountBalance ?: AccountBalance.zero(),
            regulatorySurcharges = regulatoryContext.regulatoryCharges.map { toRegulatorySurcharge(it) },
        )

        // 9. Call BillingEngine to compute the bill
        logger.info("Invoking BillingEngine for customer ${customerId.value}")
        val billResult = BillingEngine.calculateBill(billInput)

        logger.info("Bill computed successfully - Amount due: ${billResult.amountDue}")
        return billResult
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
        serviceState: String,
    ): BillResult? {
        logger.info("Computing single-service bill for customer=${customerId.value}, service=$serviceType")

        // Fetch contexts to demonstrate service integration
        val customerSnapshot = customerServiceClient.getCustomerSnapshot(
            utilityId,
            customerId,
            LocalDate.now(),
        ) ?: return null

        val rateContext = rateServiceClient.getRateContext(
            utilityId,
            serviceState,
            billingPeriod.billDate,
        ) ?: return null

        val regulatoryContext = regulatoryServiceClient.getRegulatoryContext(
            utilityId,
            serviceState,
            billingPeriod.billDate,
        ) ?: return null

        val tariff = rateContext.rateSchedules[serviceType] ?: return null

        logger.info("Successfully fetched all contexts for $serviceType billing")
        logger.info("Tariff type: ${tariff::class.simpleName}")
        logger.info("Regulatory charges: ${regulatoryContext.regulatoryCharges.size}")

        // TODO: Build BillInput and call BillingEngine
        return null
    }

    /**
     * Build MeterReadPairs from a list of meter reads.
     * Groups reads by meterId and creates pairs from consecutive reads.
     */
    private fun buildMeterReadPairs(meterReads: List<MeterRead>): List<MeterReadPair> {
        val readsByMeter = meterReads.groupBy { it.meterId }
        val pairs = mutableListOf<MeterReadPair>()

        for ((meterId, reads) in readsByMeter) {
            // Sort by date
            val sortedReads = reads.sortedBy { it.readDate }

            // Create pairs from consecutive reads
            for (i in 0 until sortedReads.size - 1) {
                val startRead = sortedReads[i]
                val endRead = sortedReads[i + 1]

                pairs.add(
                    MeterReadPair(
                        meterId = meterId,
                        serviceType = startRead.serviceType,
                        usageType = startRead.usageUnit,
                        startRead = startRead,
                        endRead = endRead,
                    ),
                )
            }
        }

        return pairs
    }

    /**
     * Convert RegulatoryCharge to RegulatorySurcharge for BillingEngine.
     */
    private fun toRegulatorySurcharge(charge: RegulatoryCharge): RegulatorySurcharge {
        val calculationType = when (charge.calculationType) {
            RegulatoryChargeType.FIXED -> RegulatorySurchargeCalculation.FIXED
            RegulatoryChargeType.PER_UNIT -> RegulatorySurchargeCalculation.PER_UNIT
            RegulatoryChargeType.PERCENTAGE_OF_ENERGY -> RegulatorySurchargeCalculation.PERCENTAGE_OF_ENERGY
            RegulatoryChargeType.PERCENTAGE_OF_TOTAL -> RegulatorySurchargeCalculation.PERCENTAGE_OF_TOTAL
        }

        return when (calculationType) {
            RegulatorySurchargeCalculation.FIXED -> {
                RegulatorySurcharge(
                    code = charge.code,
                    description = charge.description,
                    calculationType = calculationType,
                    fixedAmount = charge.rate,
                    ratePerUnit = null,
                    percentageRate = null,
                    appliesTo = emptySet(), // Apply to all services
                )
            }
            RegulatorySurchargeCalculation.PER_UNIT -> {
                RegulatorySurcharge(
                    code = charge.code,
                    description = charge.description,
                    calculationType = calculationType,
                    fixedAmount = null,
                    ratePerUnit = charge.rate,
                    percentageRate = null,
                    appliesTo = emptySet(),
                )
            }
            RegulatorySurchargeCalculation.PERCENTAGE_OF_ENERGY,
            RegulatorySurchargeCalculation.PERCENTAGE_OF_TOTAL,
            -> {
                // Convert rate (in cents) to percentage
                // Assume rate is stored as basis points (e.g., 50 = 0.5%)
                val percentage = charge.rate.amount / 100.0

                RegulatorySurcharge(
                    code = charge.code,
                    description = charge.description,
                    calculationType = calculationType,
                    fixedAmount = null,
                    ratePerUnit = null,
                    percentageRate = percentage,
                    appliesTo = emptySet(),
                )
            }
        }
    }
}

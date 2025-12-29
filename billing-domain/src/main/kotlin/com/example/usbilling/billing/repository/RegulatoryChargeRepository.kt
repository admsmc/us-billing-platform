package com.example.usbilling.billing.repository

import com.example.usbilling.billing.model.RegulatoryCharge
import com.example.usbilling.billing.model.RegulatoryChargeType
import com.example.usbilling.billing.model.ServiceType
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.UtilityId
import java.time.LocalDate

/**
 * Repository for regulatory surcharges and riders.
 * Provides jurisdiction-specific charges based on state, service type, and effective date.
 */
interface RegulatoryChargeRepository {
    /**
     * Get all applicable regulatory charges for a given jurisdiction and service type.
     *
     * @param utilityId The utility company
     * @param state State code (e.g., "MI", "OH", "IL")
     * @param serviceType Type of service being billed
     * @param asOfDate Date to evaluate charge applicability
     * @return List of applicable regulatory charges
     */
    fun getChargesForJurisdiction(
        utilityId: UtilityId,
        state: String,
        serviceType: ServiceType,
        asOfDate: LocalDate,
    ): List<RegulatoryCharge>

    /**
     * Get a specific regulatory charge by code.
     *
     * @param code Charge code (e.g., "PCA", "DSM")
     * @param state State code
     * @param asOfDate Date to evaluate charge applicability
     * @return Regulatory charge if found, null otherwise
     */
    fun getChargeByCode(
        code: String,
        state: String,
        asOfDate: LocalDate,
    ): RegulatoryCharge?
}

/**
 * In-memory implementation of RegulatoryChargeRepository with multi-state data.
 * In production, this would be replaced with database-backed or API-backed implementation.
 */
class InMemoryRegulatoryChargeRepository : RegulatoryChargeRepository {

    private val charges: Map<String, List<RegulatoryChargeEntry>> = buildChargeRegistry()

    override fun getChargesForJurisdiction(
        utilityId: UtilityId,
        state: String,
        serviceType: ServiceType,
        asOfDate: LocalDate,
    ): List<RegulatoryCharge> {
        val stateCharges = charges[state.uppercase()] ?: return emptyList()

        return stateCharges
            .filter { entry ->
                entry.serviceTypes.contains(serviceType) &&
                    !asOfDate.isBefore(entry.effectiveDate) &&
                    (entry.expirationDate == null || !asOfDate.isAfter(entry.expirationDate))
            }
            .map { it.toRegulatoryCharge() }
    }

    override fun getChargeByCode(
        code: String,
        state: String,
        asOfDate: LocalDate,
    ): RegulatoryCharge? {
        val stateCharges = charges[state.uppercase()] ?: return null

        return stateCharges
            .firstOrNull { entry ->
                entry.code == code &&
                    !asOfDate.isBefore(entry.effectiveDate) &&
                    (entry.expirationDate == null || !asOfDate.isAfter(entry.expirationDate))
            }
            ?.toRegulatoryCharge()
    }

    private data class RegulatoryChargeEntry(
        val code: String,
        val description: String,
        val calculationType: RegulatoryChargeType,
        val rate: Money,
        val serviceTypes: Set<ServiceType>,
        val effectiveDate: LocalDate,
        val expirationDate: LocalDate? = null,
    ) {
        fun toRegulatoryCharge() = RegulatoryCharge(
            code = code,
            description = description,
            calculationType = calculationType,
            rate = rate,
        )
    }

    private fun buildChargeRegistry(): Map<String, List<RegulatoryChargeEntry>> = mapOf(
        // Michigan charges
        "MI" to listOf(
            RegulatoryChargeEntry(
                code = "PCA",
                description = "Power Cost Adjustment",
                calculationType = RegulatoryChargeType.PERCENTAGE_OF_ENERGY,
                rate = Money(250), // 2.5% (stored as basis points: 250/10000)
                serviceTypes = setOf(ServiceType.ELECTRIC),
                effectiveDate = LocalDate.of(2024, 1, 1),
            ),
            RegulatoryChargeEntry(
                code = "DSM",
                description = "Demand Side Management Surcharge",
                calculationType = RegulatoryChargeType.PER_UNIT,
                rate = Money(5), // $0.05 per kWh
                serviceTypes = setOf(ServiceType.ELECTRIC),
                effectiveDate = LocalDate.of(2024, 1, 1),
            ),
            RegulatoryChargeEntry(
                code = "ECA",
                description = "Energy Conservation Assessment",
                calculationType = RegulatoryChargeType.FIXED,
                rate = Money(150), // $1.50 per month
                serviceTypes = setOf(ServiceType.ELECTRIC, ServiceType.GAS),
                effectiveDate = LocalDate.of(2024, 1, 1),
            ),
            RegulatoryChargeEntry(
                code = "LIPA",
                description = "Low Income Program Assistance",
                calculationType = RegulatoryChargeType.PERCENTAGE_OF_TOTAL,
                rate = Money(50), // 0.5%
                serviceTypes = setOf(ServiceType.ELECTRIC, ServiceType.GAS),
                effectiveDate = LocalDate.of(2024, 1, 1),
            ),
        ),

        // Ohio charges
        "OH" to listOf(
            RegulatoryChargeEntry(
                code = "UCF",
                description = "Universal Service Fund Rider",
                calculationType = RegulatoryChargeType.PERCENTAGE_OF_ENERGY,
                rate = Money(200), // 2.0%
                serviceTypes = setOf(ServiceType.ELECTRIC),
                effectiveDate = LocalDate.of(2024, 1, 1),
            ),
            RegulatoryChargeEntry(
                code = "AEC",
                description = "Advanced Energy Charge",
                calculationType = RegulatoryChargeType.PER_UNIT,
                rate = Money(8), // $0.08 per kWh
                serviceTypes = setOf(ServiceType.ELECTRIC),
                effectiveDate = LocalDate.of(2024, 1, 1),
            ),
            RegulatoryChargeEntry(
                code = "GCR",
                description = "Gas Cost Recovery",
                calculationType = RegulatoryChargeType.PERCENTAGE_OF_ENERGY,
                rate = Money(300), // 3.0%
                serviceTypes = setOf(ServiceType.GAS),
                effectiveDate = LocalDate.of(2024, 1, 1),
            ),
            RegulatoryChargeEntry(
                code = "EEC",
                description = "Energy Efficiency Charge",
                calculationType = RegulatoryChargeType.FIXED,
                rate = Money(200), // $2.00 per month
                serviceTypes = setOf(ServiceType.ELECTRIC, ServiceType.GAS),
                effectiveDate = LocalDate.of(2024, 1, 1),
            ),
        ),

        // Illinois charges
        "IL" to listOf(
            RegulatoryChargeEntry(
                code = "EPUA",
                description = "Environmental Protection User Adjustment",
                calculationType = RegulatoryChargeType.PER_UNIT,
                rate = Money(10), // $0.10 per kWh
                serviceTypes = setOf(ServiceType.ELECTRIC),
                effectiveDate = LocalDate.of(2024, 1, 1),
            ),
            RegulatoryChargeEntry(
                code = "DCRA",
                description = "Delivery Cost Recovery Adjustment",
                calculationType = RegulatoryChargeType.PERCENTAGE_OF_ENERGY,
                rate = Money(350), // 3.5%
                serviceTypes = setOf(ServiceType.ELECTRIC),
                effectiveDate = LocalDate.of(2024, 1, 1),
            ),
            RegulatoryChargeEntry(
                code = "REP",
                description = "Renewable Energy Program",
                calculationType = RegulatoryChargeType.PER_UNIT,
                rate = Money(15), // $0.15 per kWh
                serviceTypes = setOf(ServiceType.ELECTRIC),
                effectiveDate = LocalDate.of(2024, 1, 1),
            ),
            RegulatoryChargeEntry(
                code = "ICCF",
                description = "Illinois Commerce Commission Fee",
                calculationType = RegulatoryChargeType.PERCENTAGE_OF_TOTAL,
                rate = Money(100), // 1.0%
                serviceTypes = setOf(ServiceType.ELECTRIC, ServiceType.GAS, ServiceType.WATER),
                effectiveDate = LocalDate.of(2024, 1, 1),
            ),
        ),

        // California charges
        "CA" to listOf(
            RegulatoryChargeEntry(
                code = "PCIA",
                description = "Power Charge Indifference Adjustment",
                calculationType = RegulatoryChargeType.PER_UNIT,
                rate = Money(12), // $0.12 per kWh
                serviceTypes = setOf(ServiceType.ELECTRIC),
                effectiveDate = LocalDate.of(2024, 1, 1),
            ),
            RegulatoryChargeEntry(
                code = "PPP",
                description = "Public Purpose Program Surcharge",
                calculationType = RegulatoryChargeType.PERCENTAGE_OF_ENERGY,
                rate = Money(280), // 2.8%
                serviceTypes = setOf(ServiceType.ELECTRIC, ServiceType.GAS),
                effectiveDate = LocalDate.of(2024, 1, 1),
            ),
            RegulatoryChargeEntry(
                code = "CARE",
                description = "California Alternate Rates for Energy",
                calculationType = RegulatoryChargeType.PERCENTAGE_OF_TOTAL,
                rate = Money(150), // 1.5%
                serviceTypes = setOf(ServiceType.ELECTRIC, ServiceType.GAS),
                effectiveDate = LocalDate.of(2024, 1, 1),
            ),
            RegulatoryChargeEntry(
                code = "WCF",
                description = "Water Conservation Fee",
                calculationType = RegulatoryChargeType.FIXED,
                rate = Money(250), // $2.50 per month
                serviceTypes = setOf(ServiceType.WATER),
                effectiveDate = LocalDate.of(2024, 1, 1),
            ),
        ),

        // New York charges
        "NY" to listOf(
            RegulatoryChargeEntry(
                code = "MFC",
                description = "Monthly Fixed Charge",
                calculationType = RegulatoryChargeType.FIXED,
                rate = Money(1800), // $18.00 per month
                serviceTypes = setOf(ServiceType.ELECTRIC),
                effectiveDate = LocalDate.of(2024, 1, 1),
            ),
            RegulatoryChargeEntry(
                code = "NYSLR",
                description = "NY State and Local Revenue-Based Surcharges",
                calculationType = RegulatoryChargeType.PERCENTAGE_OF_TOTAL,
                rate = Money(390), // 3.9%
                serviceTypes = setOf(ServiceType.ELECTRIC, ServiceType.GAS),
                effectiveDate = LocalDate.of(2024, 1, 1),
            ),
            RegulatoryChargeEntry(
                code = "SBC",
                description = "System Benefits Charge",
                calculationType = RegulatoryChargeType.PER_UNIT,
                rate = Money(18), // $0.18 per kWh
                serviceTypes = setOf(ServiceType.ELECTRIC),
                effectiveDate = LocalDate.of(2024, 1, 1),
            ),
            RegulatoryChargeEntry(
                code = "WSC",
                description = "Water/Sewer Commodity",
                calculationType = RegulatoryChargeType.PER_UNIT,
                rate = Money(750), // $7.50 per CCF
                serviceTypes = setOf(ServiceType.WATER, ServiceType.WASTEWATER),
                effectiveDate = LocalDate.of(2024, 1, 1),
            ),
        ),
    )
}

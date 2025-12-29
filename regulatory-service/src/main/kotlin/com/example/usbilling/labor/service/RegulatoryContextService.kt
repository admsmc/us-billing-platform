package com.example.usbilling.labor.service

import com.example.usbilling.billing.model.RegulatoryCharge
import com.example.usbilling.billing.model.RegulatoryContext
import com.example.usbilling.billing.model.ServiceType
import com.example.usbilling.billing.repository.RegulatoryChargeRepository
import com.example.usbilling.labor.api.RegulatoryContextProvider
import com.example.usbilling.shared.UtilityId
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * Regulatory context service - implements RegulatoryContextProvider port.
 *
 * Assembles RegulatoryContext from the RegulatoryChargeRepository, which provides
 * jurisdiction-specific PUC charges (Power Cost Adjustment, Demand Side Management, etc.).
 */
@Service
class RegulatoryContextService(
    private val regulatoryChargeRepository: RegulatoryChargeRepository,
) : RegulatoryContextProvider {

    override fun getRegulatoryContext(
        utilityId: UtilityId,
        asOfDate: LocalDate,
        jurisdiction: String,
    ): RegulatoryContext {
        // Collect regulatory charges for all service types in this jurisdiction
        val allServiceTypes = ServiceType.entries.filter { it != ServiceType.DONATION }

        val allCharges = allServiceTypes.flatMap { serviceType ->
            regulatoryChargeRepository.getChargesForJurisdiction(
                utilityId = utilityId,
                state = jurisdiction,
                serviceType = serviceType,
                asOfDate = asOfDate,
            )
        }.distinctBy { it.code } // Remove duplicates (charges that apply to multiple services)

        return RegulatoryContext(
            utilityId = utilityId,
            jurisdiction = jurisdiction,
            regulatoryCharges = allCharges,
            effectiveDate = asOfDate,
            pucRules = emptyMap(), // Future: add PUC-specific rules if needed
        )
    }

    /**
     * Get regulatory charges for a specific service type and jurisdiction.
     * Useful for service-specific billing.
     */
    fun getChargesForService(
        utilityId: UtilityId,
        serviceType: ServiceType,
        jurisdiction: String,
        asOfDate: LocalDate,
    ): List<RegulatoryCharge> = regulatoryChargeRepository.getChargesForJurisdiction(
        utilityId = utilityId,
        state = jurisdiction,
        serviceType = serviceType,
        asOfDate = asOfDate,
    )

    /**
     * Get a specific regulatory charge by code.
     */
    fun getChargeByCode(
        code: String,
        jurisdiction: String,
        asOfDate: LocalDate,
    ): RegulatoryCharge? = regulatoryChargeRepository.getChargeByCode(
        code = code,
        state = jurisdiction,
        asOfDate = asOfDate,
    )
}

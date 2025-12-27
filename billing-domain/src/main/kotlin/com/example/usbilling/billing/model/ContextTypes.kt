package com.example.usbilling.billing.model

import com.example.usbilling.shared.UtilityId
import java.time.LocalDate

/**
 * Rate context containing all tariffs and rate information needed for billing.
 * This is the billing equivalent of TaxContext from the payroll domain.
 *
 * @property utilityId The utility company
 * @property serviceState State where service is provided
 * @property rateSchedules Map of service type to applicable rate tariff
 * @property regulatoryCharges Regulatory surcharges that apply
 * @property effectiveDate Date this rate context is effective from
 */
data class RateContext(
    val utilityId: UtilityId,
    val serviceState: String,
    val rateSchedules: Map<ServiceType, RateTariff>,
    val regulatoryCharges: List<RegulatoryCharge>,
    val effectiveDate: LocalDate
)

/**
 * Regulatory context containing jurisdiction-specific rules and charges.
 * This is the billing equivalent of LaborStandardsContext from the payroll domain.
 *
 * @property utilityId The utility company
 * @property jurisdiction State/locality code
 * @property regulatoryCharges Applicable regulatory charges for this jurisdiction
 * @property effectiveDate Date this regulatory context is effective from
 * @property pucRules Optional PUC-specific rules or identifiers
 */
data class RegulatoryContext(
    val utilityId: UtilityId,
    val jurisdiction: String,
    val regulatoryCharges: List<RegulatoryCharge>,
    val effectiveDate: LocalDate,
    val pucRules: Map<String, String> = emptyMap()
)

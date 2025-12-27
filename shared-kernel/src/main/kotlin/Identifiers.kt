package com.example.usbilling.shared

@JvmInline
value class UtilityId(val value: String)

@JvmInline
value class CustomerId(val value: String)

@JvmInline
value class BillingCycleId(val value: String)

@JvmInline
value class BillId(val value: String)

/**
 * Jurisdiction/locality code for regulatory purposes.
 * 
 * NOTE: This may be utility-specific (e.g., PUC jurisdictions, service territories).
 * Legacy use: tax locality codes from payroll domain.
 * TODO Phase 3: Evaluate if needed for billing or replace with JurisdictionCode.
 */
@JvmInline
value class LocalityCode(val value: String)

/** Helper to convert LocalityCode collections into raw code strings. */
fun Iterable<LocalityCode>.toLocalityCodeStrings(): List<String> = this.map(LocalityCode::value)

enum class CurrencyCode { USD }

data class Money(
    val amount: Long,
    val currency: CurrencyCode = CurrencyCode.USD,
)

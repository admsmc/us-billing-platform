package com.example.usbilling.shared

@JvmInline
value class UtilityId(val value: String)

@JvmInline
value class CustomerId(val value: String)

@JvmInline
value class BillRunId(val value: String)

@JvmInline
value class BillId(val value: String)

@JvmInline
value class LocalityCode(val value: String)

/** Helper to convert LocalityCode collections into raw code strings (e.g. for TaxQuery.localJurisdictions). */
fun Iterable<LocalityCode>.toLocalityCodeStrings(): List<String> = this.map(LocalityCode::value)

enum class CurrencyCode { USD }

data class Money(
    val amount: Long,
    val currency: CurrencyCode = CurrencyCode.USD,
)

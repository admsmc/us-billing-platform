package com.example.uspayroll.shared

@JvmInline
value class EmployerId(val value: String)

@JvmInline
value class EmployeeId(val value: String)

@JvmInline
value class PayRunId(val value: String)

@JvmInline
value class PaycheckId(val value: String)

@JvmInline
value class LocalityCode(val value: String)

/** Helper to convert LocalityCode collections into raw code strings (e.g. for TaxQuery.localJurisdictions). */
fun Iterable<LocalityCode>.toLocalityCodeStrings(): List<String> = this.map(LocalityCode::value)

enum class CurrencyCode { USD }

data class Money(
    val amount: Long,
    val currency: CurrencyCode = CurrencyCode.USD,
)

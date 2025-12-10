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

enum class CurrencyCode { USD }

data class Money(
    val amount: Long,
    val currency: CurrencyCode = CurrencyCode.USD,
)

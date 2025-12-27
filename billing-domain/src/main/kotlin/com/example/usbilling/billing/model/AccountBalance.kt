package com.example.usbilling.billing.model

import com.example.usbilling.shared.Money
import java.time.LocalDate

/**
 * Represents a customer's account balance state at a point in time.
 *
 * Tracks outstanding balance, payment history, and adjustments.
 *
 * @property balance Current outstanding balance (positive = customer owes, negative = credit)
 * @property lastPaymentDate Date of most recent payment (null if no payments)
 * @property lastPaymentAmount Amount of most recent payment
 * @property lastBillDate Date of most recent bill
 * @property lastBillAmount Amount of most recent bill
 * @property adjustments Manual adjustments applied to account
 * @property depositAmount Security deposit held (if applicable)
 */
data class AccountBalance(
    val balance: Money,
    val lastPaymentDate: LocalDate? = null,
    val lastPaymentAmount: Money? = null,
    val lastBillDate: LocalDate? = null,
    val lastBillAmount: Money? = null,
    val adjustments: List<BalanceAdjustment> = emptyList(),
    val depositAmount: Money? = null
) {
    /**
     * Apply a payment to the balance.
     *
     * @param amount Payment amount (positive value)
     * @param paymentDate Date payment was received
     * @return New AccountBalance with payment applied
     */
    fun applyPayment(amount: Money, paymentDate: LocalDate): AccountBalance {
        require(amount.amount >= 0) { "Payment amount must be non-negative" }
        
        return copy(
            balance = Money(balance.amount - amount.amount),
            lastPaymentDate = paymentDate,
            lastPaymentAmount = amount
        )
    }
    
    /**
     * Apply a new bill to the balance.
     *
     * @param amount Bill amount
     * @param billDate Date of bill
     * @return New AccountBalance with bill applied
     */
    fun applyBill(amount: Money, billDate: LocalDate): AccountBalance {
        return copy(
            balance = Money(balance.amount + amount.amount),
            lastBillDate = billDate,
            lastBillAmount = amount
        )
    }
    
    /**
     * Apply a manual adjustment to the balance.
     *
     * @param adjustment The adjustment to apply
     * @return New AccountBalance with adjustment applied
     */
    fun applyAdjustment(adjustment: BalanceAdjustment): AccountBalance {
        return copy(
            balance = Money(balance.amount + adjustment.amount.amount),
            adjustments = adjustments + adjustment
        )
    }
    
    /**
     * Check if account is past due.
     *
     * @param asOfDate Date to check past due status
     * @param dueDays Number of days after last bill date before considered past due
     * @return True if balance is positive and past due date
     */
    fun isPastDue(asOfDate: LocalDate, dueDays: Int = 30): Boolean {
        if (balance.amount <= 0) return false
        if (lastBillDate == null) return false
        
        val dueDate = lastBillDate.plusDays(dueDays.toLong())
        return asOfDate.isAfter(dueDate)
    }
    
    companion object {
        /**
         * Create a new account with zero balance.
         */
        fun zero(): AccountBalance = AccountBalance(balance = Money(0))
    }
}

/**
 * A manual adjustment to account balance.
 *
 * @property reason Explanation for the adjustment
 * @property amount Adjustment amount (positive = charge, negative = credit)
 * @property adjustmentDate Date adjustment was made
 * @property adjustmentType Type/category of adjustment
 * @property approvedBy User/system that approved the adjustment
 */
data class BalanceAdjustment(
    val reason: String,
    val amount: Money,
    val adjustmentDate: LocalDate,
    val adjustmentType: AdjustmentType,
    val approvedBy: String? = null
)

/**
 * Types of balance adjustments.
 */
enum class AdjustmentType {
    /** One-time credit applied to account */
    CREDIT,
    
    /** Late fee or penalty */
    LATE_FEE,
    
    /** Billing error correction */
    BILLING_CORRECTION,
    
    /** Write-off of uncollectible balance */
    WRITE_OFF,
    
    /** Returned payment fee */
    NSF_FEE,
    
    /** Customer service adjustment */
    COURTESY_CREDIT,
    
    /** Meter reading correction */
    METER_CORRECTION,
    
    /** Other adjustment */
    OTHER
}

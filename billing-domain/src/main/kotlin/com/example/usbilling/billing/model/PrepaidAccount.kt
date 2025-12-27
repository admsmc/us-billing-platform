package com.example.usbilling.billing.model

import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.UtilityId
import java.time.Instant
import java.time.LocalDate

/**
 * Account type distinguishing prepaid from postpaid billing.
 */
enum class AccountType {
    /** Customer pays after service is rendered (traditional utility billing) */
    POSTPAID,
    
    /** Customer pays in advance and usage depletes prepaid balance */
    PREPAID
}

/**
 * Prepaid account with balance that is depleted as usage occurs.
 * 
 * Unlike postpaid accounts where balance represents amount owed,
 * prepaid balance represents credit available for future consumption.
 *
 * @property utilityId The utility company
 * @property customerId The customer
 * @property serviceType Type of service (ELECTRIC, WATER, etc.)
 * @property accountType PREPAID or POSTPAID
 * @property prepaidBalance Current prepaid balance (amount available to spend)
 * @property lowBalanceThreshold Balance level that triggers low balance alerts
 * @property criticalBalanceThreshold Balance level that triggers service warning
 * @property autoRechargeEnabled Whether automatic recharge is enabled
 * @property autoRechargeAmount Amount to recharge when balance falls below threshold
 * @property autoRechargeThreshold Balance level that triggers automatic recharge
 * @property lastRechargeDate Date of most recent recharge
 * @property lastRechargeAmount Amount of most recent recharge
 * @property disconnectThreshold Balance level at which service is disconnected (typically $0)
 * @property gracePeriodDays Days of grace period after balance depleted before disconnect
 * @property status Current account status
 */
data class PrepaidAccount(
    val utilityId: UtilityId,
    val customerId: CustomerId,
    val serviceType: ServiceType,
    val accountType: AccountType,
    val prepaidBalance: Money,
    val lowBalanceThreshold: Money = Money(2000), // $20
    val criticalBalanceThreshold: Money = Money(500), // $5
    val autoRechargeEnabled: Boolean = false,
    val autoRechargeAmount: Money = Money(5000), // $50
    val autoRechargeThreshold: Money = Money(1000), // $10
    val lastRechargeDate: LocalDate? = null,
    val lastRechargeAmount: Money? = null,
    val disconnectThreshold: Money = Money(0),
    val gracePeriodDays: Int = 0,
    val status: PrepaidAccountStatus = PrepaidAccountStatus.ACTIVE
) {
    /**
     * Check if balance is below low threshold.
     */
    fun isLowBalance(): Boolean = prepaidBalance.amount < lowBalanceThreshold.amount
    
    /**
     * Check if balance is at critical level.
     */
    fun isCriticalBalance(): Boolean = prepaidBalance.amount < criticalBalanceThreshold.amount
    
    /**
     * Check if balance requires automatic recharge.
     */
    fun requiresAutoRecharge(): Boolean = 
        autoRechargeEnabled && prepaidBalance.amount < autoRechargeThreshold.amount
    
    /**
     * Check if account should be disconnected due to insufficient balance.
     */
    fun shouldDisconnect(): Boolean = prepaidBalance.amount <= disconnectThreshold.amount
    
    /**
     * Apply a recharge (add funds) to the prepaid balance.
     */
    fun applyRecharge(amount: Money, rechargeDate: LocalDate): PrepaidAccount {
        require(amount.amount > 0) { "Recharge amount must be positive" }
        
        return copy(
            prepaidBalance = Money(prepaidBalance.amount + amount.amount),
            lastRechargeDate = rechargeDate,
            lastRechargeAmount = amount,
            status = if (shouldDisconnect()) PrepaidAccountStatus.ACTIVE else status
        )
    }
    
    /**
     * Deduct usage charges from prepaid balance.
     */
    fun deductUsage(amount: Money): PrepaidAccount {
        require(amount.amount >= 0) { "Usage amount cannot be negative" }
        
        val newBalance = Money((prepaidBalance.amount - amount.amount).coerceAtLeast(0))
        val newStatus = when {
            newBalance.amount <= disconnectThreshold.amount -> PrepaidAccountStatus.DISCONNECTED
            newBalance.amount < criticalBalanceThreshold.amount -> PrepaidAccountStatus.CRITICAL
            else -> PrepaidAccountStatus.ACTIVE
        }
        
        return copy(
            prepaidBalance = newBalance,
            status = newStatus
        )
    }
    
    /**
     * Calculate days of service remaining at current usage rate.
     * 
     * @param dailyUsageCost Average daily cost of service
     * @return Estimated days remaining, or null if usage rate is zero
     */
    fun daysRemaining(dailyUsageCost: Money): Int? {
        if (dailyUsageCost.amount <= 0) return null
        return (prepaidBalance.amount / dailyUsageCost.amount).toInt()
    }
}

/**
 * Status of a prepaid account.
 */
enum class PrepaidAccountStatus {
    /** Account active with sufficient balance */
    ACTIVE,
    
    /** Balance below critical threshold but service still active */
    CRITICAL,
    
    /** Balance depleted, service disconnected */
    DISCONNECTED,
    
    /** Account suspended by customer or utility */
    SUSPENDED,
    
    /** Account closed */
    CLOSED
}

/**
 * A recharge transaction adding funds to a prepaid account.
 *
 * @property transactionId Unique transaction identifier
 * @property customerId The customer
 * @property serviceType Type of service being recharged
 * @property amount Recharge amount
 * @property transactionDate Date/time of recharge
 * @property paymentMethod How payment was made
 * @property source Source of recharge (online, kiosk, auto-recharge, etc.)
 * @property balanceBefore Balance before recharge
 * @property balanceAfter Balance after recharge
 */
data class RechargeTransaction(
    val transactionId: String,
    val customerId: CustomerId,
    val serviceType: ServiceType,
    val amount: Money,
    val transactionDate: Instant,
    val paymentMethod: PaymentMethod,
    val source: RechargeSource,
    val balanceBefore: Money,
    val balanceAfter: Money
)

/**
 * How the recharge payment was made.
 */
enum class PaymentMethod {
    CREDIT_CARD,
    DEBIT_CARD,
    BANK_ACCOUNT,
    CASH,
    CHECK,
    MOBILE_PAYMENT,
    VOUCHER,
    OTHER
}

/**
 * Source/channel of the recharge.
 */
enum class RechargeSource {
    /** Customer self-service web portal */
    ONLINE_PORTAL,
    
    /** Mobile app */
    MOBILE_APP,
    
    /** Payment kiosk */
    KIOSK,
    
    /** Over the phone */
    PHONE,
    
    /** In-person at utility office */
    IN_PERSON,
    
    /** Automatic recharge triggered by system */
    AUTO_RECHARGE,
    
    /** Third-party payment location */
    THIRD_PARTY,
    
    /** Other source */
    OTHER
}

/**
 * Real-time usage deduction record for prepaid accounts.
 * 
 * As customers use service, usage is deducted from prepaid balance
 * in near real-time based on interval meter data.
 *
 * @property customerId The customer
 * @property serviceType Type of service
 * @property deductionId Unique deduction identifier
 * @property deductionTime When deduction was processed
 * @property intervalStart Start of usage interval
 * @property intervalEnd End of usage interval
 * @property usage Amount of consumption
 * @property usageUnit Unit of measurement
 * @property cost Cost deducted from balance
 * @property balanceBefore Balance before deduction
 * @property balanceAfter Balance after deduction
 */
data class UsageDeduction(
    val customerId: CustomerId,
    val serviceType: ServiceType,
    val deductionId: String,
    val deductionTime: Instant,
    val intervalStart: Instant,
    val intervalEnd: Instant,
    val usage: Double,
    val usageUnit: UsageUnit,
    val cost: Money,
    val balanceBefore: Money,
    val balanceAfter: Money
)

/**
 * Alert notification for prepaid account events.
 *
 * @property customerId The customer
 * @property serviceType Type of service
 * @property alertType Type of alert
 * @property alertTime When alert was generated
 * @property currentBalance Current balance at time of alert
 * @property message Alert message for customer
 * @property actionRequired Whether customer action is required
 */
data class PrepaidAlert(
    val customerId: CustomerId,
    val serviceType: ServiceType,
    val alertType: PrepaidAlertType,
    val alertTime: Instant,
    val currentBalance: Money,
    val message: String,
    val actionRequired: Boolean
)

/**
 * Types of prepaid alerts.
 */
enum class PrepaidAlertType {
    /** Balance fell below low threshold */
    LOW_BALANCE,
    
    /** Balance at critical level */
    CRITICAL_BALANCE,
    
    /** Balance depleted, service will disconnect soon */
    DISCONNECT_PENDING,
    
    /** Service has been disconnected */
    DISCONNECTED,
    
    /** Automatic recharge succeeded */
    AUTO_RECHARGE_SUCCESS,
    
    /** Automatic recharge failed */
    AUTO_RECHARGE_FAILED,
    
    /** Usage rate increased significantly */
    HIGH_USAGE_DETECTED
}

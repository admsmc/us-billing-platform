package com.example.usbilling.billing.engine

import com.example.usbilling.billing.model.*
import com.example.usbilling.shared.Money
import java.time.Instant
import java.util.UUID

/**
 * Billing engine for prepaid accounts.
 *
 * Unlike postpaid billing which generates bills at end of period,
 * prepaid billing deducts usage charges from customer balance in real-time
 * as consumption occurs.
 *
 * Key differences from BillingEngine:
 * - No billing periods - charges deducted continuously
 * - Balance check before allowing consumption
 * - Real-time alerts when balance is low
 * - No account receivables - payment required upfront
 */
object PrepaidBillingEngine {

    /**
     * Process real-time usage and deduct from prepaid balance.
     *
     * Called periodically (e.g., every 15 minutes for AMI meters) to deduct
     * accumulated usage charges from customer's prepaid balance.
     *
     * @param account Current prepaid account state
     * @param intervalUsage Usage data for the interval
     * @param tariff Rate structure to apply
     * @return Updated account and deduction record
     */
    fun processIntervalUsage(
        account: PrepaidAccount,
        intervalUsage: IntervalUsage,
        tariff: RateTariff,
    ): PrepaidUsageResult {
        // Calculate cost for this interval's usage
        val cost = calculateIntervalCost(intervalUsage.usage, tariff)

        // Check if account has sufficient balance
        if (account.prepaidBalance.amount < cost.amount) {
            return PrepaidUsageResult(
                account = account,
                deduction = null,
                alert = PrepaidAlert(
                    customerId = account.customerId,
                    serviceType = account.serviceType,
                    alertType = PrepaidAlertType.CRITICAL_BALANCE,
                    alertTime = Instant.now(),
                    currentBalance = account.prepaidBalance,
                    message = "Insufficient balance for continued service. Please recharge immediately.",
                    actionRequired = true,
                ),
                insufficientBalance = true,
            )
        }

        // Deduct usage from balance
        val updatedAccount = account.deductUsage(cost)

        // Create deduction record
        val deduction = UsageDeduction(
            customerId = account.customerId,
            serviceType = account.serviceType,
            deductionId = UUID.randomUUID().toString(),
            deductionTime = Instant.now(),
            intervalStart = intervalUsage.intervalStart,
            intervalEnd = intervalUsage.intervalEnd,
            usage = intervalUsage.usage,
            usageUnit = intervalUsage.usageUnit,
            cost = cost,
            balanceBefore = account.prepaidBalance,
            balanceAfter = updatedAccount.prepaidBalance,
        )

        // Generate alerts if needed
        val alert = generateAlertIfNeeded(updatedAccount)

        return PrepaidUsageResult(
            account = updatedAccount,
            deduction = deduction,
            alert = alert,
            insufficientBalance = false,
        )
    }

    /**
     * Process a recharge transaction.
     *
     * @param account Current prepaid account state
     * @param rechargeAmount Amount to add to balance
     * @param paymentMethod How payment was made
     * @param source Where recharge originated
     * @return Updated account and transaction record
     */
    fun processRecharge(
        account: PrepaidAccount,
        rechargeAmount: Money,
        paymentMethod: PaymentMethod,
        source: RechargeSource,
    ): PrepaidRechargeResult {
        require(rechargeAmount.amount > 0) { "Recharge amount must be positive" }

        val balanceBefore = account.prepaidBalance
        val updatedAccount = account.applyRecharge(rechargeAmount, java.time.LocalDate.now())

        val transaction = RechargeTransaction(
            transactionId = UUID.randomUUID().toString(),
            customerId = account.customerId,
            serviceType = account.serviceType,
            amount = rechargeAmount,
            transactionDate = Instant.now(),
            paymentMethod = paymentMethod,
            source = source,
            balanceBefore = balanceBefore,
            balanceAfter = updatedAccount.prepaidBalance,
        )

        return PrepaidRechargeResult(
            account = updatedAccount,
            transaction = transaction,
            wasAutoRecharge = source == RechargeSource.AUTO_RECHARGE,
        )
    }

    /**
     * Check if account requires automatic recharge and process if needed.
     *
     * @param account Current prepaid account state
     * @param paymentMethod Payment method configured for auto-recharge
     * @return Recharge result if auto-recharge was triggered, null otherwise
     */
    fun checkAndProcessAutoRecharge(
        account: PrepaidAccount,
        paymentMethod: PaymentMethod,
    ): PrepaidRechargeResult? {
        if (!account.requiresAutoRecharge()) {
            return null
        }

        return try {
            processRecharge(
                account = account,
                rechargeAmount = account.autoRechargeAmount,
                paymentMethod = paymentMethod,
                source = RechargeSource.AUTO_RECHARGE,
            )
        } catch (e: Exception) {
            // Auto-recharge failed - generate alert
            null
        }
    }

    /**
     * Calculate the cost of usage for a single interval.
     *
     * Simplified calculation - in production would need to handle:
     * - Time-of-use rates (peak/off-peak)
     * - Tiered rates
     * - Demand charges
     * - Prorated readiness-to-serve charges
     */
    private fun calculateIntervalCost(usage: Double, tariff: RateTariff): Money = when (tariff) {
        is RateTariff.FlatRate -> {
            Money((usage * tariff.ratePerUnit.amount).toLong())
        }
        is RateTariff.TieredRate -> {
            // Simplified: use first tier rate
            val rate = tariff.tiers.firstOrNull()?.ratePerUnit ?: Money(0)
            Money((usage * rate.amount).toLong())
        }
        is RateTariff.TimeOfUseRate -> {
            // Simplified: use peak rate
            Money((usage * tariff.peakRate.amount).toLong())
        }
        is RateTariff.DemandRate -> {
            Money((usage * tariff.energyRatePerUnit.amount).toLong())
        }
    }

    /**
     * Generate alert if account status requires customer notification.
     */
    private fun generateAlertIfNeeded(account: PrepaidAccount): PrepaidAlert? = when {
        account.shouldDisconnect() -> PrepaidAlert(
            customerId = account.customerId,
            serviceType = account.serviceType,
            alertType = PrepaidAlertType.DISCONNECTED,
            alertTime = Instant.now(),
            currentBalance = account.prepaidBalance,
            message = "Service disconnected due to insufficient balance. Please recharge to resume service.",
            actionRequired = true,
        )
        account.isCriticalBalance() -> PrepaidAlert(
            customerId = account.customerId,
            serviceType = account.serviceType,
            alertType = PrepaidAlertType.CRITICAL_BALANCE,
            alertTime = Instant.now(),
            currentBalance = account.prepaidBalance,
            message = "Critical: Your balance is very low. Service may be disconnected soon.",
            actionRequired = true,
        )
        account.isLowBalance() -> PrepaidAlert(
            customerId = account.customerId,
            serviceType = account.serviceType,
            alertType = PrepaidAlertType.LOW_BALANCE,
            alertTime = Instant.now(),
            currentBalance = account.prepaidBalance,
            message = "Low balance alert: Please recharge soon to avoid service interruption.",
            actionRequired = false,
        )
        else -> null
    }
}

/**
 * Result of processing prepaid usage deduction.
 *
 * @property account Updated prepaid account state
 * @property deduction Usage deduction record (null if insufficient balance)
 * @property alert Alert generated (if any)
 * @property insufficientBalance Whether deduction failed due to insufficient balance
 */
data class PrepaidUsageResult(
    val account: PrepaidAccount,
    val deduction: UsageDeduction?,
    val alert: PrepaidAlert?,
    val insufficientBalance: Boolean,
)

/**
 * Result of processing prepaid recharge.
 *
 * @property account Updated prepaid account state
 * @property transaction Recharge transaction record
 * @property wasAutoRecharge Whether this was an automatic recharge
 */
data class PrepaidRechargeResult(
    val account: PrepaidAccount,
    val transaction: RechargeTransaction,
    val wasAutoRecharge: Boolean,
)

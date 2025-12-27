package com.example.usbilling.billing.model

import com.example.usbilling.shared.Money
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountBalanceTest {
    
    @Test
    fun `zero creates account with zero balance`() {
        val account = AccountBalance.zero()
        
        assertEquals(Money(0), account.balance)
        assertEquals(null, account.lastPaymentDate)
        assertEquals(null, account.lastBillDate)
        assertTrue(account.adjustments.isEmpty())
    }
    
    @Test
    fun `applyPayment reduces balance and records payment`() {
        val account = AccountBalance(balance = Money(10000)) // $100 owed
        val paymentDate = LocalDate.of(2025, 1, 15)
        
        val updated = account.applyPayment(Money(5000), paymentDate) // Pay $50
        
        assertEquals(Money(5000), updated.balance) // $50 remaining
        assertEquals(paymentDate, updated.lastPaymentDate)
        assertEquals(Money(5000), updated.lastPaymentAmount)
    }
    
    @Test
    fun `applyBill increases balance and records bill`() {
        val account = AccountBalance(balance = Money(2000)) // $20 prior balance
        val billDate = LocalDate.of(2025, 1, 31)
        
        val updated = account.applyBill(Money(7500), billDate) // New bill $75
        
        assertEquals(Money(9500), updated.balance) // $95 total owed
        assertEquals(billDate, updated.lastBillDate)
        assertEquals(Money(7500), updated.lastBillAmount)
    }
    
    @Test
    fun `applyAdjustment modifies balance and tracks adjustment`() {
        val account = AccountBalance(balance = Money(10000))
        val adjustment = BalanceAdjustment(
            reason = "Courtesy credit for service interruption",
            amount = Money(-2000), // $20 credit
            adjustmentDate = LocalDate.of(2025, 1, 20),
            adjustmentType = AdjustmentType.COURTESY_CREDIT,
            approvedBy = "CSR-123"
        )
        
        val updated = account.applyAdjustment(adjustment)
        
        assertEquals(Money(8000), updated.balance) // $80 remaining
        assertEquals(1, updated.adjustments.size)
        assertEquals(adjustment, updated.adjustments.first())
    }
    
    @Test
    fun `isPastDue returns true when balance positive and past due date`() {
        val billDate = LocalDate.of(2025, 1, 1)
        val account = AccountBalance(
            balance = Money(5000),
            lastBillDate = billDate
        )
        
        val checkDate = LocalDate.of(2025, 2, 15) // 45 days later
        
        assertTrue(account.isPastDue(checkDate, dueDays = 30))
    }
    
    @Test
    fun `isPastDue returns false when balance zero or negative`() {
        val billDate = LocalDate.of(2025, 1, 1)
        val account = AccountBalance(
            balance = Money(0),
            lastBillDate = billDate
        )
        
        val checkDate = LocalDate.of(2025, 2, 15)
        
        assertFalse(account.isPastDue(checkDate, dueDays = 30))
    }
    
    @Test
    fun `isPastDue returns false when not yet past due date`() {
        val billDate = LocalDate.of(2025, 1, 1)
        val account = AccountBalance(
            balance = Money(5000),
            lastBillDate = billDate
        )
        
        val checkDate = LocalDate.of(2025, 1, 15) // Only 14 days later
        
        assertFalse(account.isPastDue(checkDate, dueDays = 30))
    }
    
    @Test
    fun `multiple adjustments accumulate`() {
        var account = AccountBalance(balance = Money(10000))
        
        val adj1 = BalanceAdjustment(
            reason = "Late fee",
            amount = Money(500), // $5 late fee
            adjustmentDate = LocalDate.of(2025, 1, 10),
            adjustmentType = AdjustmentType.LATE_FEE
        )
        
        val adj2 = BalanceAdjustment(
            reason = "Billing correction",
            amount = Money(-1000), // $10 credit
            adjustmentDate = LocalDate.of(2025, 1, 15),
            adjustmentType = AdjustmentType.BILLING_CORRECTION
        )
        
        account = account.applyAdjustment(adj1)
        account = account.applyAdjustment(adj2)
        
        assertEquals(Money(9500), account.balance) // $100 + $5 - $10 = $95
        assertEquals(2, account.adjustments.size)
    }
    
    @Test
    fun `realistic billing cycle scenario`() {
        // Start with zero balance
        var account = AccountBalance.zero()
        
        // First bill: $75.50
        account = account.applyBill(Money(7550), LocalDate.of(2025, 1, 31))
        assertEquals(Money(7550), account.balance)
        
        // Customer pays $75.50
        account = account.applyPayment(Money(7550), LocalDate.of(2025, 2, 10))
        assertEquals(Money(0), account.balance)
        
        // Second bill: $82.25
        account = account.applyBill(Money(8225), LocalDate.of(2025, 2, 28))
        assertEquals(Money(8225), account.balance)
        
        // Customer only pays $50
        account = account.applyPayment(Money(5000), LocalDate.of(2025, 3, 15))
        assertEquals(Money(3225), account.balance)
        
        // Third bill: $79.00, plus prior balance
        account = account.applyBill(Money(7900), LocalDate.of(2025, 3, 31))
        assertEquals(Money(11125), account.balance) // $32.25 + $79.00
        
        assertFalse(account.isPastDue(LocalDate.of(2025, 4, 15), dueDays = 30))
        assertTrue(account.isPastDue(LocalDate.of(2025, 5, 15), dueDays = 30))
    }
}

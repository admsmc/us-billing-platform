# Prepaid vs. Postpaid Account Support

## Overview

The platform supports **both prepaid and postpaid** billing models, providing flexibility for different utility business models and customer preferences.

| Feature | Prepaid | Postpaid |
|---------|---------|----------|
| **Payment Timing** | Pay before usage | Pay after usage |
| **Balance Type** | Credit (funds available) | Debit (amount owed) |
| **Billing Frequency** | Real-time deductions | Periodic bills (monthly/bimonthly/etc.) |
| **Usage Control** | Service stops when balance depleted | Usage continues regardless of balance |
| **Customer Risk** | Lower (prepayment required) | Higher (credit extended) |
| **Deposit Required** | Typically no | Often required for poor credit |
| **Late Fees** | Not applicable | Yes, if payment delayed |
| **Disconnect Risk** | Immediate if balance = $0 | Only after non-payment for extended period |
| **Credit Check** | Not required | Often required |

## Postpaid Accounts (Traditional)

### Architecture
```
┌────────────────────────────────────────────────┐
│  Postpaid Billing Cycle                        │
│                                                │
│  1. Service Period (e.g., Jan 1-31)           │
│  2. Meter Read (Jan 31)                       │
│  3. Bill Generated (Feb 1)                    │
│  4. Payment Due (Feb 20)                      │
│  5. Collections if unpaid                     │
└────────────────────────────────────────────────┘

Uses: BillingEngine.calculateBill()
Balance: Positive = customer owes utility
```

### Key Models
- `BillingPeriod` - Designated service period
- `BillResult` - Final bill at end of period
- `AccountBalance` - Tracks amount owed
- `ChargeCategory` - Different charge types

### Example Workflow
```kotlin
// Month of service (postpaid)
val billingPeriod = BillingPeriod(
    startDate = LocalDate.of(2025, 12, 1),
    endDate = LocalDate.of(2025, 12, 31),
    billDate = LocalDate.of(2025, 12, 31),
    dueDate = LocalDate.of(2026, 1, 20)
)

// Generate bill at end of period
val bill = BillingEngine.calculateMultiServiceBill(input)
// Customer owes: $115.06
// Due date: January 20, 2026

// Customer pays bill
val updatedBalance = accountBalance.applyPayment(
    amount = Money(11506),
    paymentDate = LocalDate.of(2026, 1, 15)
)
```

## Prepaid Accounts (Pay-as-you-go)

### Architecture
```
┌────────────────────────────────────────────────┐
│  Prepaid Usage Cycle                           │
│                                                │
│  1. Customer recharges account ($50)          │
│  2. Usage occurs (15-min intervals)           │
│  3. Charges deducted in real-time             │
│  4. Balance decreases continuously            │
│  5. Alerts when balance low                   │
│  6. Service stops if balance = $0             │
└────────────────────────────────────────────────┘

Uses: PrepaidBillingEngine.processIntervalUsage()
Balance: Positive = credit available for usage
```

### Key Models
- `PrepaidAccount` - Account with prepaid balance
- `RechargeTransaction` - Payment adding funds
- `UsageDeduction` - Real-time usage charge
- `PrepaidAlert` - Balance/service alerts
- `AccountType` - PREPAID vs POSTPAID enum

### Example Workflow
```kotlin
// Create prepaid account
val account = PrepaidAccount(
    utilityId = utilityId,
    customerId = customerId,
    serviceType = ServiceType.ELECTRIC,
    accountType = AccountType.PREPAID,
    prepaidBalance = Money(0),
    lowBalanceThreshold = Money(2000),  // $20
    criticalBalanceThreshold = Money(500), // $5
    autoRechargeEnabled = true,
    autoRechargeAmount = Money(5000), // Auto-add $50
    autoRechargeThreshold = Money(1000) // When below $10
)

// Customer recharges
val rechargeResult = PrepaidBillingEngine.processRecharge(
    account = account,
    rechargeAmount = Money(5000), // $50
    paymentMethod = PaymentMethod.CREDIT_CARD,
    source = RechargeSource.ONLINE_PORTAL
)
// Balance: $50.00

// Every 15 minutes, deduct usage
val intervalUsage = IntervalUsage(
    meterId = "ELEC-001",
    serviceType = ServiceType.ELECTRIC,
    intervalStart = Instant.parse("2025-12-27T10:00:00Z"),
    intervalEnd = Instant.parse("2025-12-27T10:15:00Z"),
    usage = 2.5, // kWh in 15 minutes
    usageUnit = UsageUnit.KWH,
    cost = Money(30) // Calculated cost
)

val usageResult = PrepaidBillingEngine.processIntervalUsage(
    account = rechargeResult.account,
    intervalUsage = intervalUsage,
    tariff = electricTariff
)
// Balance after: $49.70
// Deduction: $0.30
```

## Feature Comparison

### Prepaid Features

**Balance Management:**
- ✅ Real-time balance deductions
- ✅ Multiple recharge channels (online, mobile, kiosk, phone)
- ✅ Auto-recharge when balance low
- ✅ Days of service remaining calculator
- ✅ Balance alerts (low/critical/disconnected)

**Status Tracking:**
- `ACTIVE` - Sufficient balance
- `CRITICAL` - Below critical threshold
- `DISCONNECTED` - Balance depleted
- `SUSPENDED` - Manually suspended
- `CLOSED` - Account closed

**Alert Types:**
- `LOW_BALANCE` - Balance below $20 (configurable)
- `CRITICAL_BALANCE` - Balance below $5 (configurable)
- `DISCONNECT_PENDING` - Service will stop soon
- `DISCONNECTED` - Service stopped
- `AUTO_RECHARGE_SUCCESS/FAILED` - Auto-recharge status
- `HIGH_USAGE_DETECTED` - Unusual usage pattern

**Payment Methods:**
- Credit/debit card
- Bank account
- Cash (at kiosk/office)
- Mobile payment
- Voucher
- Auto-recharge

**Recharge Sources:**
- Online portal
- Mobile app
- Payment kiosk
- Phone (IVR)
- In-person at office
- Auto-recharge (system)
- Third-party locations

### Postpaid Features

**Billing Periods:**
- MONTHLY
- BIMONTHLY
- QUARTERLY
- ANNUAL

**Balance Adjustments:**
- `CREDIT` - One-time credit
- `LATE_FEE` - Payment delay penalty
- `BILLING_CORRECTION` - Error fix
- `WRITE_OFF` - Uncollectible
- `NSF_FEE` - Returned payment
- `COURTESY_CREDIT` - Customer service adjustment
- `METER_CORRECTION` - Meter error fix

**Account Features:**
- Payment history tracking
- Past due detection
- Security deposits
- Collections workflow
- Payment plans
- Budget billing

## Use Cases

### When to Use Prepaid

✅ **Good For:**
- Customers with poor/no credit history
- Customers who prefer pay-as-you-go
- Budget-conscious customers
- Temporary service (vacation homes, construction sites)
- Customers with past payment issues
- Markets with high collection costs
- Low-income assistance programs

❌ **Not Ideal For:**
- Customers without regular income
- Areas without good recharge infrastructure
- Services requiring continuous uptime (hospitals, etc.)
- Customers unable to manage frequent recharges

### When to Use Postpaid

✅ **Good For:**
- Customers with good credit
- Commercial/industrial accounts
- Stable residential customers
- Long-term service relationships
- Budget billing programs
- Levelized payment plans

❌ **Not Ideal For:**
- High-risk customers
- Markets with high collection costs
- Customers preferring consumption control
- Short-term service needs

## Implementation Examples

### Prepaid Real-Time Deduction

```kotlin
// Setup prepaid account with smart meter
val prepaidAccount = PrepaidAccount(
    utilityId = UtilityId("util-001"),
    customerId = CustomerId("cust-12345"),
    serviceType = ServiceType.ELECTRIC,
    accountType = AccountType.PREPAID,
    prepaidBalance = Money(5000), // $50 initial
    autoRechargeEnabled = true,
    autoRechargeAmount = Money(5000),
    autoRechargeThreshold = Money(1000)
)

// Process 15-minute interval (AMI meter)
val result = PrepaidBillingEngine.processIntervalUsage(
    account = prepaidAccount,
    intervalUsage = intervalData,
    tariff = flatRateTariff
)

when {
    result.insufficientBalance -> {
        // Trigger disconnect or emergency recharge
        println("Service interrupted - insufficient balance")
    }
    result.alert != null -> {
        // Send alert to customer
        notificationService.send(result.alert)
    }
    else -> {
        // Normal operation
        println("Balance: ${result.account.prepaidBalance}")
    }
}

// Check for auto-recharge
val autoRecharge = PrepaidBillingEngine.checkAndProcessAutoRecharge(
    account = result.account,
    paymentMethod = PaymentMethod.CREDIT_CARD
)
```

### Postpaid Periodic Billing

```kotlin
// Setup postpaid account with monthly billing
val billingPeriod = BillingPeriod(
    id = "202512",
    utilityId = utilityId,
    startDate = LocalDate.of(2025, 12, 1),
    endDate = LocalDate.of(2025, 12, 31),
    billDate = LocalDate.of(2025, 12, 31),
    dueDate = LocalDate.of(2026, 1, 20),
    frequency = BillingFrequency.MONTHLY
)

// Generate bill at end of period
val bill = BillingEngine.calculateMultiServiceBill(
    input = MultiServiceBillInput(
        billId = BillId("BILL-202512-12345"),
        billPeriod = billingPeriod,
        serviceReads = serviceReads,
        serviceTariffs = tariffs,
        accountBalance = currentBalance
    )
)

// Bill generated: $115.06
// Customer has until Jan 20 to pay
```

## Customer Experience Comparison

### Prepaid Customer Journey

1. **Activation**: Pay initial balance, no credit check
2. **Usage**: Service active, balance decreases in real-time
3. **Monitoring**: Check balance anytime via portal/app
4. **Alerts**: Receive low balance notifications
5. **Recharge**: Add funds online/kiosk/phone when needed
6. **Control**: Know exactly how much spending, no surprises

**Pros:** No deposits, no credit check, budget control, no bills
**Cons:** Must monitor balance, service stops if not recharged

### Postpaid Customer Journey

1. **Activation**: Credit check, possible deposit required
2. **Usage**: Service active for entire billing period
3. **Billing**: Receive bill at end of month
4. **Payment**: Pay by due date (typically 20 days later)
5. **Collections**: Late fees if payment delayed

**Pros:** Convenient, no balance monitoring, consistent service
**Cons:** Bills can be surprising, late fees, collections risk

## Hybrid Model

Some utilities offer **both** account types:

```kotlin
// Customer can have both prepaid and postpaid services
val customer = Customer(
    customerId = CustomerId("cust-12345"),
    accounts = listOf(
        // Prepaid electric (customer preference)
        PrepaidAccount(
            serviceType = ServiceType.ELECTRIC,
            accountType = AccountType.PREPAID,
            prepaidBalance = Money(5000)
        ),
        // Postpaid water (bundled with other services)
        PostpaidAccount(
            serviceType = ServiceType.WATER,
            accountType = AccountType.POSTPAID,
            balance = Money(0)
        )
    )
)
```

## Testing

```bash
# Test prepaid billing engine
./gradlew :billing-domain:test --tests "*Prepaid*"

# Test postpaid billing engine  
./gradlew :billing-domain:test --tests "*BillingEngine*"
```

## References

- [Prepaid Utility Metering Best Practices](https://www.naruc.org)
- [Smart Meter Integration for Prepaid](https://www.energy.gov/oe/advanced-metering-infrastructure)
- [Consumer Protection in Prepaid Service](https://www.nclc.org)

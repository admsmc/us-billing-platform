# Michigan Public Utility Multi-Service Billing Guide

## Overview

This guide describes how to use the billing platform to generate bills for Michigan public utilities offering multiple services (electric, water, wastewater, broadband, etc.) with Michigan-specific regulatory surcharges and voluntary contribution programs.

## Architecture

### Multi-Service Billing

The platform supports multi-service billing through:
- **ServiceType** enum: Defines service categories (ELECTRIC, WATER, WASTEWATER, BROADBAND, etc.)
- **MultiServiceBillInput**: Encapsulates meter readings and tariffs for multiple services
- **ServiceMeterReads**: Groups meter readings by service type
- **BillingEngine.calculateMultiServiceBill()**: Core calculation method

### Service Types

Available service types (see `ServiceTypes.kt`):
```kotlin
enum class ServiceType {
    ELECTRIC,      // Electric power service
    WATER,         // Potable water service
    WASTEWATER,    // Sewer/wastewater service
    BROADBAND,     // Internet/telecommunications
    GAS,           // Natural gas service
    REFUSE,        // Trash collection
    RECYCLING,     // Recycling collection
    STORMWATER,    // Stormwater management
    DONATION       // Voluntary contributions
}
```

### Usage Units

Supported usage units for metering:
```kotlin
enum class UsageUnit {
    KWH,          // Kilowatt-hours (electric)
    KW,           // Kilowatts (demand)
    CCF,          // Hundred cubic feet (water/gas)
    GALLONS,      // Gallons (water)
    THERMS,       // Therms (gas)
    MBPS,         // Megabits per second (broadband)
    GB,           // Gigabytes (broadband data)
    CUBIC_YARDS,  // Cubic yards (refuse)
    CONTAINERS,   // Containers (recycling)
    NONE          // No metered usage (flat-rate services)
}
```

## Michigan Regulatory Surcharges

Michigan utilities must apply regulatory surcharges mandated by the Michigan Public Service Commission (MPSC).

### Electric Service Surcharges

Implemented in `MichiganElectricSurcharges` object:

#### Power Supply Cost Recovery (PSCR)
- **Code**: PSCR
- **Type**: Per-unit charge
- **Default Rate**: $0.00125/kWh
- **Purpose**: Recovers fuel and purchased power costs
- **Regulation**: MPSC approval required; rates adjusted periodically

#### System Access Fee (SAF)
- **Code**: SAF
- **Type**: Fixed monthly charge
- **Default Amount**: $7.00/month
- **Purpose**: Covers distribution system maintenance and operations

#### Low Income Energy Assistance Program (LIHEAP)
- **Code**: LIHEAP
- **Type**: Percentage of energy charges
- **Default Rate**: 0.5%
- **Purpose**: Funds assistance programs for low-income customers

#### Energy Optimization (EO)
- **Code**: EO
- **Type**: Percentage of energy charges
- **Default Rate**: 2.0%
- **Purpose**: Funds energy efficiency and conservation programs

#### Renewable Energy Standard (RES)
- **Code**: RES
- **Type**: Per-unit charge
- **Default Rate**: $0.0005/kWh
- **Purpose**: Supports renewable energy development

### Water and Wastewater Surcharges

Implemented in `MichiganWaterSurcharges` object:

#### Infrastructure Improvement Charge
- **Code**: INFRA
- **Type**: Percentage of total charges
- **Default Rate**: 2.0%
- **Purpose**: Funds water/sewer infrastructure upgrades
- **Applies To**: WATER, WASTEWATER

#### Lead Service Line Replacement (LSLR)
- **Code**: LSLR
- **Type**: Fixed monthly charge
- **Default Amount**: $3.00/month
- **Purpose**: Funds lead pipe replacement programs
- **Applies To**: WATER

#### Stormwater Management Fee
- **Code**: STORM
- **Type**: Fixed monthly charge
- **Default Amount**: $5.00/month
- **Purpose**: Funds stormwater infrastructure
- **Applies To**: WASTEWATER, STORMWATER

## Voluntary Contributions

Utilities can offer optional voluntary contribution programs.

### Contribution Programs

Available programs (see `ContributionProgram` enum):
- **ENERGY_ASSISTANCE**: Low-income energy assistance
- **TREE_PLANTING**: Urban forestry and tree planting
- **RENEWABLE_ENERGY**: Renewable energy development
- **LOW_INCOME_SUPPORT**: General customer support
- **COMMUNITY_FUND**: Community improvement
- **CONSERVATION**: Environmental conservation
- **EDUCATION**: Customer education and outreach
- **OTHER**: Other programs

### Example

```kotlin
val contributions = listOf(
    VoluntaryContribution(
        code = "ENERGY_ASSIST",
        description = "Energy Assistance Program",
        amount = Money(500),  // $5.00
        program = ContributionProgram.ENERGY_ASSISTANCE
    )
)
```

## Example: Michigan Multi-Service Bill

### Scenario

Residential customer with:
- Electric service: 800 kWh usage
- Water service: 15 CCF usage
- Wastewater service: 15 CCF usage (typically equals water usage)
- Broadband service: 100 Mbps plan (flat rate, no metered usage)
- $5.00 voluntary donation to energy assistance

### Code Example

```kotlin
// Electric: 800 kWh with tiered rates
val electricReads = ServiceMeterReads(
    serviceType = ServiceType.ELECTRIC,
    reads = listOf(
        MeterReadPair(
            meterId = "ELEC-001",
            serviceType = ServiceType.ELECTRIC,
            usageType = UsageUnit.KWH,
            startRead = MeterRead("ELEC-001", ServiceType.ELECTRIC, 45200.0, 
                LocalDate.of(2025, 12, 1), UsageUnit.KWH),
            endRead = MeterRead("ELEC-001", ServiceType.ELECTRIC, 46000.0, 
                LocalDate.of(2025, 12, 31), UsageUnit.KWH)
        )
    )
)

// Electric tariff: Tiered residential rate
val electricTariff = RateTariff.TieredRate(
    readinessToServeCharge = Money(1500),  // $15.00/month
    tiers = listOf(
        RateTier(maxUsage = 500.0, ratePerUnit = Money(10)),  // $0.10/kWh ≤ 500
        RateTier(maxUsage = null, ratePerUnit = Money(12))     // $0.12/kWh > 500
    ),
    unit = "kWh"
)

// Michigan regulatory surcharges
val surcharges = listOf(
    MichiganElectricSurcharges.powerSupplyCostRecovery(),
    MichiganElectricSurcharges.systemAccessFee(),
    MichiganElectricSurcharges.liheapSurcharge(),
    MichiganWaterSurcharges.infrastructureCharge()
)

// Voluntary contribution
val contributions = listOf(
    VoluntaryContribution(
        code = "ENERGY_ASSIST",
        description = "Energy Assistance Program",
        amount = Money(500),
        program = ContributionProgram.ENERGY_ASSISTANCE
    )
)

val input = MultiServiceBillInput(
    billId = BillId("BILL-202512-12345"),
    billRunId = BillRunId("RUN-202512"),
    utilityId = UtilityId("michigan-utility-001"),
    customerId = CustomerId("customer-12345"),
    billPeriod = billPeriod,
    serviceReads = listOf(electricReads, waterReads, wastewaterReads, broadbandReads),
    serviceTariffs = mapOf(
        ServiceType.ELECTRIC to electricTariff,
        ServiceType.WATER to waterTariff,
        ServiceType.WASTEWATER to wastewaterTariff,
        ServiceType.BROADBAND to broadbandTariff
    ),
    accountBalance = AccountBalance.zero(),
    regulatorySurcharges = surcharges,
    contributions = contributions
)

val result = BillingEngine.calculateMultiServiceBill(input)
```

### Expected Bill Breakdown

```
Electric Service:
  Readiness to Serve              $15.00
  Usage (500 kWh @ $0.10)     $50.00
  Usage (300 kWh @ $0.12)     $36.00
  PSCR (800 kWh @ $0.00125)    $1.00
  SAF                          $7.00
  LIHEAP (0.5% of usage)       $0.43
  --------------------------------
  Electric Subtotal          $109.43

Water Service:
  Readiness to Serve              $8.00
  Usage (15 CCF @ $3.50)      $52.50
  INFRA (2% of subtotal)       $1.21
  LSLR                         $3.00
  --------------------------------
  Water Subtotal              $64.71

Wastewater Service:
  Readiness to Serve              $8.00
  Usage (15 CCF @ $4.00)      $60.00
  INFRA (2% of subtotal)       $1.36
  STORM                        $5.00
  --------------------------------
  Wastewater Subtotal         $74.36

Broadband Service:
  Monthly Service (100 Mbps)  $49.99
  --------------------------------
  Broadband Subtotal          $49.99

Voluntary Contributions:
  Energy Assistance            $5.00
  --------------------------------

TOTAL AMOUNT DUE             $303.49
```

## Demo Endpoint

Test the Michigan multi-service billing via the demo HTTP endpoint:

```bash
curl http://localhost:8080/demo/michigan-multi-service-bill
```

This returns a complete BillResult JSON with all service charges, regulatory surcharges, and contributions calculated.

## Testing

Comprehensive test coverage is provided:

### Unit Tests
- `MichiganMultiServiceBillTest`: Multi-service billing scenarios
- `MichiganRegulatoryChargesTest`: Surcharge configuration validation
- `VoluntaryContributionTest`: Contribution model tests

### Running Tests
```bash
./gradlew :billing-domain:test --tests "*Michigan*"
```

## References

- [Michigan Public Service Commission](https://www.michigan.gov/mpsc)
- [MPSC Electric Rate Cases](https://mi-psc.my.site.com/sfc/#version?selectedDocumentId=0692X000001n1lx)
- [Michigan Energy Optimization Programs](https://www.michigan.gov/mpsc/consumer/electricity/energy-efficiency)
- [Lead and Copper Rule](https://www.epa.gov/dwreginfo/lead-and-copper-rule)

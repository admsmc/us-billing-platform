package com.example.usbilling.hr.http

import com.example.usbilling.billing.model.BillingPeriod
import com.example.usbilling.billing.model.CustomerSnapshot
import com.example.usbilling.billing.model.MeterRead
import com.example.usbilling.hr.domain.BillingPeriodEntity
import com.example.usbilling.hr.domain.CustomerEntity
import com.example.usbilling.hr.domain.MeterEntity
import com.example.usbilling.hr.domain.MeterReadEntity
import com.example.usbilling.hr.repository.BillingPeriodRepository
import com.example.usbilling.hr.repository.CustomerRepository
import com.example.usbilling.hr.repository.MeterReadRepository
import com.example.usbilling.hr.repository.MeterRepository
import com.example.usbilling.hr.service.CustomerService
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.*

/**
 * REST API for customer data management.
 */
@RestController
@RequestMapping("/utilities/{utilityId}")
class CustomerController(
    private val customerService: CustomerService,
    private val customerRepository: CustomerRepository,
    private val meterRepository: MeterRepository,
    private val billingPeriodRepository: BillingPeriodRepository,
    private val meterReadRepository: MeterReadRepository,
) {

    /**
     * Get customer snapshot (implements port interface via HTTP).
     */
    @GetMapping("/customers/{customerId}/snapshot")
    fun getCustomerSnapshot(
        @PathVariable utilityId: String,
        @PathVariable customerId: String,
        @RequestParam(required = false) asOfDate: String?,
    ): ResponseEntity<CustomerSnapshot> {
        val date = asOfDate?.let { LocalDate.parse(it) } ?: LocalDate.now()

        val snapshot = customerService.getCustomerSnapshot(
            UtilityId(utilityId),
            CustomerId(customerId),
            date,
        )

        return if (snapshot != null) {
            ResponseEntity.ok(snapshot)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Get billing period details (implements port interface via HTTP).
     */
    @GetMapping("/customers/{customerId}/billing-periods/{periodId}")
    fun getBillingPeriod(
        @PathVariable utilityId: String,
        @PathVariable customerId: String,
        @PathVariable periodId: String,
    ): ResponseEntity<BillingPeriodResponse> {
        val period = customerService.getBillingPeriod(UtilityId(utilityId), periodId)
            ?: return ResponseEntity.notFound().build()

        val meterReads = customerService.getMeterReads(periodId)

        return ResponseEntity.ok(BillingPeriodResponse(period, meterReads))
    }

    /**
     * Create a new customer.
     */
    @PostMapping("/customers")
    fun createCustomer(
        @PathVariable utilityId: String,
        @RequestBody request: CreateCustomerRequest,
    ): ResponseEntity<CustomerEntity> {
        val customerId = UUID.randomUUID().toString()

        val customerEntity = CustomerEntity(
            customerId = customerId,
            utilityId = utilityId,
            accountNumber = request.accountNumber,
            customerName = request.customerName,
            serviceAddress = request.serviceAddress,
            customerClass = request.customerClass,
            active = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

        val saved = customerRepository.save(customerEntity)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved)
    }

    /**
     * Add a meter to a customer.
     */
    @PostMapping("/customers/{customerId}/meters")
    fun addMeter(
        @PathVariable utilityId: String,
        @PathVariable customerId: String,
        @RequestBody request: AddMeterRequest,
    ): ResponseEntity<MeterEntity> {
        // Verify customer exists and belongs to utility
        val customer = customerRepository.findById(customerId).orElse(null)
            ?: return ResponseEntity.notFound().build()

        if (customer.utilityId != utilityId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        val meterId = UUID.randomUUID().toString()

        val meterEntity = MeterEntity(
            meterId = meterId,
            customerId = customerId,
            utilityServiceType = request.utilityServiceType,
            meterNumber = request.meterNumber,
            installDate = request.installDate ?: LocalDate.now(),
            removalDate = null,
            active = true,
            createdAt = Instant.now(),
        )

        val saved = meterRepository.save(meterEntity)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved)
    }

    /**
     * Record a meter reading.
     */
    @PostMapping("/customers/{customerId}/meter-reads")
    fun recordMeterRead(
        @PathVariable utilityId: String,
        @PathVariable customerId: String,
        @RequestBody request: RecordMeterReadRequest,
    ): ResponseEntity<MeterReadEntity> {
        // Verify meter exists and belongs to customer
        val meter = meterRepository.findById(request.meterId).orElse(null)
            ?: return ResponseEntity.notFound().build()

        if (meter.customerId != customerId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        val readId = UUID.randomUUID().toString()

        val meterReadEntity = MeterReadEntity(
            readId = readId,
            meterId = request.meterId,
            billingPeriodId = request.billingPeriodId,
            readDate = request.readDate,
            readingValue = request.readingValue,
            readingType = request.readingType,
            recordedAt = Instant.now(),
        )

        val saved = meterReadRepository.save(meterReadEntity)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved)
    }

    /**
     * Create a billing period for a customer.
     */
    @PostMapping("/customers/{customerId}/billing-periods")
    fun createBillingPeriod(
        @PathVariable utilityId: String,
        @PathVariable customerId: String,
        @RequestBody request: CreateBillingPeriodRequest,
    ): ResponseEntity<BillingPeriodEntity> {
        // Verify customer exists and belongs to utility
        val customer = customerRepository.findById(customerId).orElse(null)
            ?: return ResponseEntity.notFound().build()

        if (customer.utilityId != utilityId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        val periodId = UUID.randomUUID().toString()

        val periodEntity = BillingPeriodEntity(
            periodId = periodId,
            customerId = customerId,
            startDate = request.startDate,
            endDate = request.endDate,
            status = request.status ?: "OPEN",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

        val saved = billingPeriodRepository.save(periodEntity)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved)
    }

    /**
     * List all customers for a utility.
     */
    @GetMapping("/customers")
    fun listCustomers(@PathVariable utilityId: String): ResponseEntity<List<CustomerEntity>> {
        val customers = customerRepository.findByUtilityId(utilityId)
        return ResponseEntity.ok(customers)
    }

    /**
     * List billing periods for a customer.
     */
    @GetMapping("/customers/{customerId}/billing-periods")
    fun listBillingPeriods(
        @PathVariable utilityId: String,
        @PathVariable customerId: String,
    ): ResponseEntity<List<BillingPeriodEntity>> {
        val periods = billingPeriodRepository.findByCustomerId(customerId)
        return ResponseEntity.ok(periods)
    }

    /**
     * List meters for a customer.
     */
    @GetMapping("/customers/{customerId}/meters")
    fun listMeters(
        @PathVariable utilityId: String,
        @PathVariable customerId: String,
    ): ResponseEntity<List<MeterEntity>> {
        val meters = meterRepository.findByCustomerId(customerId)
        return ResponseEntity.ok(meters)
    }
}

// Request/Response DTOs

data class CreateCustomerRequest(
    val accountNumber: String,
    val customerName: String,
    val serviceAddress: String,
    val customerClass: String?,
)

data class AddMeterRequest(
    val utilityServiceType: String,
    val meterNumber: String,
    val installDate: LocalDate?,
)

data class RecordMeterReadRequest(
    val meterId: String,
    val billingPeriodId: String?,
    val readDate: LocalDate,
    val readingValue: BigDecimal,
    val readingType: String,
)

data class CreateBillingPeriodRequest(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val status: String?,
)

data class BillingPeriodResponse(
    val period: BillingPeriod,
    val meterReads: List<MeterRead>,
)

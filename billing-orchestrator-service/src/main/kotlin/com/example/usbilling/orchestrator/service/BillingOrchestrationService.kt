package com.example.usbilling.orchestrator.service

import com.example.usbilling.billing.model.BillResult
import com.example.usbilling.billing.model.ChargeLineItem
import com.example.usbilling.orchestrator.jobs.BillingJobPublisher
import com.example.usbilling.orchestrator.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.*

/**
 * Billing orchestration service - manages bill lifecycle.
 *
 * Handles bill creation, status transitions, and lifecycle operations.
 */
@Service
class BillingOrchestrationService(
    private val billRepository: BillRepository,
    private val billLineRepository: BillLineRepository,
    private val billEventRepository: BillEventRepository,
    private val billingJobPublisher: BillingJobPublisher
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Create a draft bill for a customer and billing period.
     */
    @Transactional
    fun createDraftBill(
        customerId: String,
        utilityId: String,
        billingPeriodId: String,
        billDate: LocalDate,
        dueDate: LocalDate
    ): BillEntity {
        val billId = UUID.randomUUID().toString()
        
        val bill = BillEntity(
            billId = billId,
            customerId = customerId,
            utilityId = utilityId,
            billingPeriodId = billingPeriodId,
            billNumber = null, // Assigned when finalized
            status = "DRAFT",
            totalAmountCents = 0, // Updated when computed
            dueDate = dueDate,
            billDate = billDate,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        
        val saved = billRepository.save(bill)
        
        // Record event
        recordEvent(billId, "BILL_CREATED", "Draft bill created")
        
        logger.info("Created draft bill $billId for customer $customerId")
        return saved
    }

    /**
     * Update bill status.
     */
    @Transactional
    fun updateBillStatus(billId: String, newStatus: String): BillEntity? {
        val bill = billRepository.findById(billId).orElse(null) ?: return null
        
        val updated = bill.copy(
            status = newStatus,
            updatedAt = Instant.now()
        )
        
        val saved = billRepository.save(updated)
        recordEvent(billId, "STATUS_CHANGED", "Status changed to $newStatus")
        
        logger.info("Updated bill $billId status to $newStatus")
        return saved
    }

    /**
     * Void a bill.
     */
    @Transactional
    fun voidBill(billId: String, reason: String): BillEntity? {
        val bill = billRepository.findById(billId).orElse(null) ?: return null
        
        if (bill.status == "VOIDED") {
            logger.warn("Bill $billId is already voided")
            return bill
        }
        
        val voided = bill.copy(
            status = "VOIDED",
            updatedAt = Instant.now()
        )
        
        val saved = billRepository.save(voided)
        recordEvent(billId, "BILL_VOIDED", "Bill voided: $reason")
        
        logger.info("Voided bill $billId")
        return saved
    }

    /**
     * Get bill with line items.
     */
    fun getBillWithLines(billId: String): BillWithLines? {
        val bill = billRepository.findById(billId).orElse(null) ?: return null
        val lines = billLineRepository.findByBillId(billId)
        val events = billEventRepository.findByBillId(billId)
        
        return BillWithLines(bill, lines, events)
    }

    /**
     * List bills for a customer.
     */
    fun listCustomerBills(customerId: String): List<BillEntity> {
        return billRepository.findByCustomerId(customerId)
    }

    /**
     * Trigger asynchronous bill computation.
     * Publishes a message to the queue for worker processing.
     *
     * @param billId Bill to compute
     * @param serviceState State for rate/regulatory context lookup
     * @return Updated bill entity with COMPUTING status
     */
    @Transactional
    fun triggerBillComputation(billId: String, serviceState: String): BillEntity? {
        val bill = billRepository.findById(billId).orElse(null) ?: return null
        
        // Update status to COMPUTING
        val computing = updateBillStatus(billId, "COMPUTING")
        
        // Publish message to queue for worker to process
        val job = billingJobPublisher.createComputeBillJob(
            billId = bill.billId,
            utilityId = bill.utilityId,
            customerId = bill.customerId,
            billingPeriodId = bill.billingPeriodId,
            serviceState = serviceState
        )
        
        billingJobPublisher.publishComputeBillJob(job)
        
        logger.info("Triggered async bill computation for bill $billId (messageId: ${job.messageId})")
        return computing
    }
    
    /**
     * Finalize a bill with computation results.
     * Updates bill with totals and persists line items.
     */
    @Transactional
    fun finalizeBill(billId: String, billResult: BillResult): BillEntity? {
        val bill = billRepository.findById(billId).orElse(null) ?: return null
        
        // Update bill with computed totals
        val finalized = bill.copy(
            status = "FINALIZED",
            totalAmountCents = billResult.amountDue.amount,
            billNumber = generateBillNumber(bill.utilityId, bill.customerId),
            updatedAt = Instant.now()
        )
        
        val saved = billRepository.save(finalized)
        
        // Persist bill line items
        persistBillLines(billId, billResult.charges)
        
        // Record event
        recordEvent(billId, "BILL_FINALIZED", "Bill finalized with amount: ${billResult.amountDue}")
        
        logger.info("Finalized bill $billId with ${billResult.charges.size} line items, total: ${billResult.amountDue}")
        return saved
    }
    
    /**
     * Persist bill line items from charge line items.
     */
    private fun persistBillLines(billId: String, charges: List<ChargeLineItem>) {
        charges.forEachIndexed { index, charge ->
            val lineEntity = BillLineEntity(
                lineId = UUID.randomUUID().toString(),
                billId = billId,
                serviceType = charge.serviceType?.name ?: "GENERAL",
                chargeType = charge.category.name,
                description = charge.description,
                usageAmount = charge.usageAmount?.let { BigDecimal.valueOf(it) },
                rateValueCents = charge.rate?.amount,
                lineAmountCents = charge.amount.amount,
                lineOrder = index
            )
            billLineRepository.save(lineEntity)
        }
    }
    
    /**
     * Generate a unique bill number.
     */
    private fun generateBillNumber(utilityId: String, customerId: String): String {
        val timestamp = System.currentTimeMillis()
        return "BILL-${utilityId}-${customerId}-${timestamp}"
    }
    
    private fun recordEvent(billId: String, eventType: String, description: String) {
        val event = BillEventEntity(
            eventId = UUID.randomUUID().toString(),
            billId = billId,
            eventType = eventType,
            eventData = description,
            createdAt = Instant.now()
        )
        billEventRepository.save(event)
    }
}

/**
 * Bill with associated line items and events.
 */
data class BillWithLines(
    val bill: BillEntity,
    val lines: List<BillLineEntity>,
    val events: List<BillEventEntity>
)

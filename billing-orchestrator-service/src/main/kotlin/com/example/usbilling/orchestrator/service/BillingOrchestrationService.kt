package com.example.usbilling.orchestrator.service

import com.example.usbilling.orchestrator.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
    private val billEventRepository: BillEventRepository
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

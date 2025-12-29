package com.example.usbilling.orchestrator.http

import com.example.usbilling.orchestrator.domain.BillEntity
import com.example.usbilling.orchestrator.service.BillingOrchestrationService
import com.example.usbilling.orchestrator.service.BillWithLines
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@RestController
@RequestMapping("/utilities/{utilityId}")
class BillingOrchestratorController(
    private val billingOrchestrationService: BillingOrchestrationService
) {

    @PostMapping("/bills")
    fun createDraftBill(
        @PathVariable utilityId: String,
        @RequestBody request: CreateBillRequest
    ): ResponseEntity<BillEntity> {
        val bill = billingOrchestrationService.createDraftBill(
            customerId = request.customerId,
            utilityId = utilityId,
            billingPeriodId = request.billingPeriodId,
            billDate = request.billDate,
            dueDate = request.dueDate
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(bill)
    }

    @GetMapping("/bills/{billId}")
    fun getBill(
        @PathVariable utilityId: String,
        @PathVariable billId: String
    ): ResponseEntity<BillWithLines> {
        val bill = billingOrchestrationService.getBillWithLines(billId)
        return if (bill != null) {
            ResponseEntity.ok(bill)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @GetMapping("/customers/{customerId}/bills")
    fun listCustomerBills(
        @PathVariable utilityId: String,
        @PathVariable customerId: String
    ): ResponseEntity<List<BillEntity>> {
        val bills = billingOrchestrationService.listCustomerBills(customerId)
        return ResponseEntity.ok(bills)
    }

    @PostMapping("/bills/{billId}/void")
    fun voidBill(
        @PathVariable utilityId: String,
        @PathVariable billId: String,
        @RequestBody request: VoidBillRequest
    ): ResponseEntity<BillEntity> {
        val bill = billingOrchestrationService.voidBill(billId, request.reason)
        return if (bill != null) {
            ResponseEntity.ok(bill)
        } else {
            ResponseEntity.notFound().build()
        }
    }
    
    @PostMapping("/bills/{billId}/finalize")
    fun finalizeBill(
        @PathVariable utilityId: String,
        @PathVariable billId: String,
        @RequestBody request: FinalizeBillRequest
    ): ResponseEntity<BillEntity> {
        val bill = billingOrchestrationService.triggerBillComputation(billId, request.serviceState)
        return if (bill != null) {
            // Return 202 Accepted - computation is async
            ResponseEntity.accepted().body(bill)
        } else {
            ResponseEntity.notFound().build()
        }
    }
}

data class CreateBillRequest(
    val customerId: String,
    val billingPeriodId: String,
    val billDate: LocalDate,
    val dueDate: LocalDate
)

data class VoidBillRequest(
    val reason: String
)

data class FinalizeBillRequest(
    val serviceState: String
)

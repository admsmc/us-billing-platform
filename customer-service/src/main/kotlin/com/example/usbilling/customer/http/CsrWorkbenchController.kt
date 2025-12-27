package com.example.usbilling.customer.http

import com.example.usbilling.customer.model.*
import com.example.usbilling.customer.repository.JdbcCaseRepository
import com.example.usbilling.customer.repository.JdbcCustomer360Repository
import com.example.usbilling.shared.CustomerId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/csr")
class CsrWorkbenchController(
    private val caseRepository: JdbcCaseRepository,
    private val customer360Repository: JdbcCustomer360Repository,
) {
    
    /**
     * Get cases assigned to the current CSR.
     * GET /csr/my-queue
     */
    @GetMapping("/my-queue")
    fun getMyCases(
        @RequestHeader("X-CSR-ID") csrId: String,
        @RequestParam(defaultValue = "100") limit: Int
    ): ResponseEntity<CsrQueueResponse> {
        val cases = caseRepository.findByCsrId(
            csrId = csrId,
            statuses = listOf(CaseStatus.OPEN, CaseStatus.IN_PROGRESS),
            limit = limit
        )
        
        return ResponseEntity.ok(
            CsrQueueResponse(
                queueType = "MY_QUEUE",
                csrId = csrId,
                cases = cases,
                totalCount = cases.size
            )
        )
    }
    
    /**
     * Get cases assigned to the CSR's team.
     * GET /csr/team-queue
     */
    @GetMapping("/team-queue")
    fun getTeamQueue(
        @RequestHeader("X-CSR-ID") csrId: String,
        @RequestHeader("X-TEAM-ID") teamId: String,
        @RequestParam(defaultValue = "100") limit: Int
    ): ResponseEntity<CsrQueueResponse> {
        val cases = caseRepository.findByTeamId(
            teamId = teamId,
            statuses = listOf(CaseStatus.OPEN, CaseStatus.IN_PROGRESS),
            limit = limit
        )
        
        return ResponseEntity.ok(
            CsrQueueResponse(
                queueType = "TEAM_QUEUE",
                teamId = teamId,
                cases = cases,
                totalCount = cases.size
            )
        )
    }
    
    /**
     * Get comprehensive 360-degree view of a customer.
     * GET /csr/accounts/{customerId}/360-view
     */
    @GetMapping("/accounts/{customerId}/360-view")
    fun getCustomer360View(
        @PathVariable customerId: String,
        @RequestHeader("X-CSR-ID") csrId: String
    ): ResponseEntity<Customer360View> {
        val view = customer360Repository.getCustomer360View(CustomerId(customerId))
            ?: return ResponseEntity.notFound().build()
        
        return ResponseEntity.ok(view)
    }
    
    /**
     * Perform common CSR quick actions on an account.
     * POST /csr/accounts/{customerId}/quick-actions/{action}
     */
    @PostMapping("/accounts/{customerId}/quick-actions/{action}")
    fun performQuickAction(
        @PathVariable customerId: String,
        @PathVariable action: String,
        @RequestHeader("X-CSR-ID") csrId: String,
        @RequestBody(required = false) request: QuickActionRequest?
    ): ResponseEntity<QuickActionResponse> {
        return when (action.uppercase()) {
            "CREATE_PAYMENT_ARRANGEMENT" -> {
                // Placeholder - would integrate with payment service
                ResponseEntity.ok(
                    QuickActionResponse(
                        success = true,
                        message = "Payment arrangement created",
                        actionType = action
                    )
                )
            }
            "SCHEDULE_CALLBACK" -> {
                // Placeholder - would create follow-up task
                ResponseEntity.ok(
                    QuickActionResponse(
                        success = true,
                        message = "Callback scheduled for ${request?.callbackDate}",
                        actionType = action
                    )
                )
            }
            "SEND_BILL_COPY" -> {
                // Placeholder - would trigger bill regeneration
                ResponseEntity.ok(
                    QuickActionResponse(
                        success = true,
                        message = "Bill copy sent to customer",
                        actionType = action
                    )
                )
            }
            "REQUEST_METER_READ" -> {
                // Placeholder - would create field service request
                ResponseEntity.ok(
                    QuickActionResponse(
                        success = true,
                        message = "Meter read request submitted",
                        actionType = action
                    )
                )
            }
            "ADD_ACCOUNT_NOTE" -> {
                // Placeholder - would add note to account
                ResponseEntity.ok(
                    QuickActionResponse(
                        success = true,
                        message = "Note added to account",
                        actionType = action
                    )
                )
            }
            else -> ResponseEntity.badRequest().body(
                QuickActionResponse(
                    success = false,
                    message = "Unknown action: $action",
                    actionType = action
                )
            )
        }
    }
    
    /**
     * Get CSR performance metrics.
     * GET /csr/metrics
     */
    @GetMapping("/metrics")
    fun getCsrMetrics(
        @RequestHeader("X-CSR-ID") csrId: String
    ): ResponseEntity<CsrMetrics> {
        // Placeholder - would calculate real metrics
        return ResponseEntity.ok(
            CsrMetrics(
                csrId = csrId,
                activeCaseCount = caseRepository.findByCsrId(csrId).size,
                avgResolutionTimeMinutes = 45,
                casesResolvedToday = 12,
                customerSatisfactionScore = 4.5,
                slaComplianceRate = 0.95
            )
        )
    }
}

data class CsrQueueResponse(
    val queueType: String,
    val csrId: String? = null,
    val teamId: String? = null,
    val cases: List<CaseRecord>,
    val totalCount: Int
)

data class QuickActionRequest(
    val notes: String? = null,
    val callbackDate: String? = null,
    val amount: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class QuickActionResponse(
    val success: Boolean,
    val message: String,
    val actionType: String,
    val resultId: String? = null
)

data class CsrMetrics(
    val csrId: String,
    val activeCaseCount: Int,
    val avgResolutionTimeMinutes: Int,
    val casesResolvedToday: Int,
    val customerSatisfactionScore: Double,
    val slaComplianceRate: Double
)

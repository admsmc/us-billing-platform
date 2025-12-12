package com.example.uspayroll.hr.http.admin

import com.example.uspayroll.hr.garnishment.GarnishmentOrderOverrideAuditService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin/garnishments/overrides")
@ConditionalOnProperty(
    prefix = "hr.audit.garnishment-overrides",
    name = ["http-enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class GarnishmentOverrideAuditAdminController(
    private val auditService: GarnishmentOrderOverrideAuditService,
) {

    data class AuditResponse(
        val rowsChecked: Int,
        val isValid: Boolean,
        val errorCount: Int,
        val errors: List<ErrorView>,
    )

    data class ErrorView(
        val orderId: String,
        val message: String,
    )

    @GetMapping("/audit")
    fun audit(): AuditResponse {
        val report = auditService.auditAllOrders()
        val errors = report.errors
            .take(200)
            .map { ErrorView(orderId = it.orderId, message = it.message) }

        return AuditResponse(
            rowsChecked = report.rowsChecked,
            isValid = report.isValid,
            errorCount = report.errors.size,
            errors = errors,
        )
    }
}

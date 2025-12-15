package com.example.uspayroll.hr.audit

import com.example.uspayroll.web.WebMdcKeys
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.MDC
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class HrAuditService(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {

    data class AuditContext(
        val correlationId: String? = MDC.get(WebMdcKeys.CORRELATION_ID),
        val principalSub: String? = MDC.get(WebMdcKeys.PRINCIPAL_SUB),
        val principalScope: String? = MDC.get(WebMdcKeys.PRINCIPAL_SCOPE),
        val idempotencyKey: String? = null,
    )

    fun record(
        employerId: String,
        entityType: String,
        entityId: String,
        action: String,
        effectiveFrom: java.time.LocalDate? = null,
        before: Any? = null,
        after: Any? = null,
        ctx: AuditContext = AuditContext(),
    ) {
        val beforeJson = before?.let { objectMapper.writeValueAsString(it) }
        val afterJson = after?.let { objectMapper.writeValueAsString(it) }

        jdbcTemplate.update(
            """
            INSERT INTO hr_audit_event (
                employer_id,
                entity_type,
                entity_id,
                action,
                effective_from,
                correlation_id,
                principal_sub,
                principal_scope,
                idempotency_key,
                before_json,
                after_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            employerId,
            entityType,
            entityId,
            action,
            effectiveFrom,
            ctx.correlationId,
            ctx.principalSub,
            ctx.principalScope,
            ctx.idempotencyKey,
            beforeJson,
            afterJson,
        )
    }
}

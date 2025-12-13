package com.example.uspayroll.orchestrator.persistence

import com.example.uspayroll.payroll.model.audit.PaycheckAudit
import com.example.uspayroll.shared.EmployerId

interface PaycheckAuditStoreRepository {
    fun insertAuditIfAbsent(audit: PaycheckAudit)

    fun findAudit(employerId: EmployerId, paycheckId: String): PaycheckAudit?
}

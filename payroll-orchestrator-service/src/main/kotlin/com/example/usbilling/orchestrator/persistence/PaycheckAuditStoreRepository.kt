package com.example.usbilling.orchestrator.persistence

import com.example.usbilling.payroll.model.audit.PaycheckAudit
import com.example.usbilling.shared.EmployerId

interface PaycheckAuditStoreRepository {
    fun insertAuditIfAbsent(audit: PaycheckAudit)

    fun findAudit(employerId: EmployerId, paycheckId: String): PaycheckAudit?
}

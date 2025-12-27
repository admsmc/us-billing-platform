package com.example.usbilling.orchestrator.persistence

import com.example.usbilling.payroll.model.audit.PaycheckAudit
import com.example.usbilling.shared.UtilityId

interface PaycheckAuditStoreRepository {
    fun insertAuditIfAbsent(audit: PaycheckAudit)

    fun findAudit(employerId: UtilityId, paycheckId: String): PaycheckAudit?
}

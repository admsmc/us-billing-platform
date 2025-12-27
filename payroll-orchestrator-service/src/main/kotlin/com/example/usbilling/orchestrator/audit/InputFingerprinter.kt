package com.example.usbilling.orchestrator.audit

import com.example.usbilling.payroll.model.EarningCode
import com.example.usbilling.payroll.model.EarningInput
import com.example.usbilling.payroll.model.EmployeeSnapshot
import com.example.usbilling.payroll.model.TaxContext
import com.example.usbilling.payroll.model.audit.InputFingerprint
import com.example.usbilling.payroll.model.audit.PaycheckAudit
import com.example.usbilling.payroll.model.config.DeductionConfigRepository
import com.example.usbilling.payroll.model.config.DeductionPlan
import com.example.usbilling.payroll.model.config.EarningConfigRepository
import com.example.usbilling.payroll.model.config.EarningDefinition
import com.example.usbilling.shared.UtilityId
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.springframework.stereotype.Component
import java.security.MessageDigest

@Component
class InputFingerprinter(objectMapper: ObjectMapper) {

    private val canonicalObjectMapper: ObjectMapper = objectMapper.copy()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)

    data class EarningDefinitionFingerprint(
        val code: String,
        val definition: EarningDefinition?,
    )

    data class EarningConfigFingerprintPayload(
        val employerId: String,
        val definitions: List<EarningDefinitionFingerprint>,
    )

    data class DeductionConfigFingerprintPayload(
        val employerId: String,
        val plans: List<DeductionPlan>,
    )

    fun stamp(
        audit: PaycheckAudit,
        employerId: UtilityId,
        employeeSnapshot: EmployeeSnapshot,
        taxContext: TaxContext,
        laborStandards: Any?,
        earningOverrides: List<EarningInput>,
        earningConfigRepository: EarningConfigRepository,
        deductionConfigRepository: DeductionConfigRepository,
    ): PaycheckAudit {
        val employeeSnapshotFingerprint = fingerprint(employeeSnapshot)
        val taxContextFingerprint = fingerprint(taxContext)
        val laborStandardsFingerprint = fingerprintOrUnknown(laborStandards)
        val earningConfigFingerprint = earningConfigFingerprint(
            employerId = employerId,
            earningOverrides = earningOverrides,
            repo = earningConfigRepository,
        )
        val deductionConfigFingerprint = deductionConfigFingerprint(
            employerId = employerId,
            repo = deductionConfigRepository,
        )

        return audit.copy(
            employeeSnapshotFingerprint = employeeSnapshotFingerprint,
            taxContextFingerprint = taxContextFingerprint,
            laborStandardsFingerprint = laborStandardsFingerprint,
            earningConfigFingerprint = earningConfigFingerprint,
            deductionConfigFingerprint = deductionConfigFingerprint,
        )
    }

    private fun earningConfigFingerprint(employerId: UtilityId, earningOverrides: List<EarningInput>, repo: EarningConfigRepository): InputFingerprint {
        // TODO(enterprise-config): replace with a stable config version ID once backed by a config service.
        val codes: Set<EarningCode> = buildSet {
            add(EarningCode("BASE"))
            add(EarningCode("HOURLY"))
            earningOverrides.forEach { add(it.code) }
        }

        val definitions = codes
            .map { code ->
                EarningDefinitionFingerprint(
                    code = code.value,
                    definition = repo.findByEmployerAndCode(employerId, code),
                )
            }
            .sortedBy { it.code }

        return fingerprint(
            EarningConfigFingerprintPayload(
                employerId = employerId.value,
                definitions = definitions,
            ),
        )
    }

    private fun deductionConfigFingerprint(employerId: UtilityId, repo: DeductionConfigRepository): InputFingerprint {
        // TODO(enterprise-config): replace with a stable config version ID once backed by a config service.
        val plans = repo.findPlansForEmployer(employerId)
            .sortedBy { it.id }

        return fingerprint(
            DeductionConfigFingerprintPayload(
                employerId = employerId.value,
                plans = plans,
            ),
        )
    }

    private fun fingerprintOrUnknown(value: Any?): InputFingerprint {
        if (value == null) return InputFingerprint.UNKNOWN
        return fingerprint(value)
    }

    private fun fingerprint(value: Any): InputFingerprint {
        val bytes = canonicalObjectMapper.writeValueAsBytes(value)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return InputFingerprint(
            version = InputFingerprint.UNKNOWN_VALUE,
            sha256 = digest.toHexLower(),
        )
    }

    private fun ByteArray.toHexLower(): String {
        val out = CharArray(size * 2)
        var i = 0
        for (b in this) {
            val v = b.toInt() and 0xFF
            val hi = v ushr 4
            val lo = v and 0x0F
            out[i++] = "0123456789abcdef"[hi]
            out[i++] = "0123456789abcdef"[lo]
        }
        return String(out)
    }
}

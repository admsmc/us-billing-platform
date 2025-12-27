package com.example.usbilling.payroll.model.audit

/**
 * Controls how much calculation metadata is produced.
 *
 * Enterprise-grade convention:
 * - DEBUG is for developer diagnostics only.
 * - AUDIT is the production default for committed payroll runs and must preserve auditability via [PaycheckAudit]
 *   rather than verbose step-by-step traces.
 * - NONE should be used only for non-authoritative previews.
 */
enum class TraceLevel {
    NONE,
    AUDIT,
    DEBUG,
}

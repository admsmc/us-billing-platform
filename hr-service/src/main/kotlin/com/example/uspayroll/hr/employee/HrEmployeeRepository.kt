package com.example.uspayroll.hr.employee

import com.example.uspayroll.payroll.model.EmploymentType
import com.example.uspayroll.payroll.model.FilingStatus
import com.example.uspayroll.payroll.model.FlsaExemptStatus
import com.example.uspayroll.payroll.model.PayFrequency
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

@Repository
class HrEmployeeRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    data class EmployeeCreate(
        val employerId: String,
        val employeeId: String,
        val firstName: String?,
        val lastName: String?,
        val profileEffectiveFrom: LocalDate,
        val homeState: String,
        val workState: String,
        val workCity: String?,
        val filingStatus: FilingStatus,
        val employmentType: EmploymentType,
        val hireDate: LocalDate?,
        val terminationDate: LocalDate?,
        val dependents: Int?,
        val federalWithholdingExempt: Boolean,
        val isNonresidentAlien: Boolean,
        val w4AnnualCreditCents: Long?,
        val w4OtherIncomeCents: Long?,
        val w4DeductionsCents: Long?,
        val w4Step2MultipleJobs: Boolean,
        val w4Version: String?,
        val legacyAllowances: Int?,
        val legacyAdditionalWithholdingCents: Long?,
        val legacyMaritalStatus: String?,
        val w4EffectiveDate: LocalDate?,
        val additionalWithholdingCents: Long?,
        val ficaExempt: Boolean,
        val flsaEnterpriseCovered: Boolean,
        val flsaExemptStatus: FlsaExemptStatus,
        val isTippedEmployee: Boolean,
    )

    data class EmployeeProfilePatch(
        val effectiveFrom: LocalDate,
        val homeState: String?,
        val workState: String?,
        val workCity: String?,
        val filingStatus: FilingStatus?,
        val employmentType: EmploymentType?,
        val hireDate: LocalDate?,
        val terminationDate: LocalDate?,
        val dependents: Int?,
        val federalWithholdingExempt: Boolean?,
        val isNonresidentAlien: Boolean?,
        val w4AnnualCreditCents: Long?,
        val w4OtherIncomeCents: Long?,
        val w4DeductionsCents: Long?,
        val w4Step2MultipleJobs: Boolean?,
        val w4Version: String?,
        val legacyAllowances: Int?,
        val legacyAdditionalWithholdingCents: Long?,
        val legacyMaritalStatus: String?,
        val w4EffectiveDate: LocalDate?,
        val additionalWithholdingCents: Long?,
        val ficaExempt: Boolean?,
        val flsaEnterpriseCovered: Boolean?,
        val flsaExemptStatus: FlsaExemptStatus?,
        val isTippedEmployee: Boolean?,
    )

    data class EmployeeProfileRow(
        val employerId: String,
        val employeeId: String,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate,
        val systemFrom: Instant,
        val systemTo: Instant,
        val homeState: String,
        val workState: String,
        val workCity: String?,
        val filingStatus: String,
        val employmentType: String,
        val hireDate: LocalDate?,
        val terminationDate: LocalDate?,
        val dependents: Int?,
        val federalWithholdingExempt: Boolean,
        val isNonresidentAlien: Boolean,
        val w4AnnualCreditCents: Long?,
        val w4OtherIncomeCents: Long?,
        val w4DeductionsCents: Long?,
        val w4Step2MultipleJobs: Boolean,
        val w4Version: String?,
        val legacyAllowances: Int?,
        val legacyAdditionalWithholdingCents: Long?,
        val legacyMaritalStatus: String?,
        val w4EffectiveDate: LocalDate?,
        val additionalWithholdingCents: Long?,
        val ficaExempt: Boolean,
        val flsaEnterpriseCovered: Boolean,
        val flsaExemptStatus: String,
        val isTippedEmployee: Boolean,
    )

    fun employeeExists(employerId: String, employeeId: String): Boolean {
        val count = jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM employee WHERE employer_id = ? AND employee_id = ?",
            Long::class.java,
            employerId,
            employeeId,
        ) ?: 0L
        return count > 0L
    }

    fun createEmployee(cmd: EmployeeCreate) {
        jdbcTemplate.update(
            """
            INSERT INTO employee (
                employer_id,
                employee_id,
                first_name,
                last_name,
                home_state,
                work_state,
                work_city,
                filing_status,
                employment_type,
                hire_date,
                termination_date,
                dependents,
                federal_withholding_exempt,
                is_nonresident_alien,
                w4_annual_credit_cents,
                w4_other_income_cents,
                w4_deductions_cents,
                w4_step2_multiple_jobs,
                w4_version,
                legacy_allowances,
                legacy_additional_withholding_cents,
                legacy_marital_status,
                w4_effective_date,
                additional_withholding_cents,
                fica_exempt,
                flsa_enterprise_covered,
                flsa_exempt_status,
                is_tipped_employee
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            cmd.employerId,
            cmd.employeeId,
            cmd.firstName,
            cmd.lastName,
            cmd.homeState,
            cmd.workState,
            cmd.workCity,
            cmd.filingStatus.name,
            cmd.employmentType.name,
            cmd.hireDate,
            cmd.terminationDate,
            cmd.dependents,
            cmd.federalWithholdingExempt,
            cmd.isNonresidentAlien,
            cmd.w4AnnualCreditCents,
            cmd.w4OtherIncomeCents,
            cmd.w4DeductionsCents,
            cmd.w4Step2MultipleJobs,
            cmd.w4Version,
            cmd.legacyAllowances,
            cmd.legacyAdditionalWithholdingCents,
            cmd.legacyMaritalStatus,
            cmd.w4EffectiveDate,
            cmd.additionalWithholdingCents,
            cmd.ficaExempt,
            cmd.flsaEnterpriseCovered,
            cmd.flsaExemptStatus.name,
            cmd.isTippedEmployee,
        )

        // Create the initial effective-dated profile. (V008 backfill covers legacy rows; for new rows we insert explicitly.)
        jdbcTemplate.update(
            """
            INSERT INTO employee_profile_effective (
                employer_id,
                employee_id,
                effective_from,
                effective_to,
                home_state,
                work_state,
                work_city,
                filing_status,
                employment_type,
                hire_date,
                termination_date,
                dependents,
                federal_withholding_exempt,
                is_nonresident_alien,
                w4_annual_credit_cents,
                w4_other_income_cents,
                w4_deductions_cents,
                w4_step2_multiple_jobs,
                w4_version,
                legacy_allowances,
                legacy_additional_withholding_cents,
                legacy_marital_status,
                w4_effective_date,
                additional_withholding_cents,
                fica_exempt,
                flsa_enterprise_covered,
                flsa_exempt_status,
                is_tipped_employee
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            cmd.employerId,
            cmd.employeeId,
            cmd.profileEffectiveFrom,
            LocalDate.of(9999, 12, 31),
            cmd.homeState,
            cmd.workState,
            cmd.workCity,
            cmd.filingStatus.name,
            cmd.employmentType.name,
            cmd.hireDate,
            cmd.terminationDate,
            cmd.dependents,
            cmd.federalWithholdingExempt,
            cmd.isNonresidentAlien,
            cmd.w4AnnualCreditCents,
            cmd.w4OtherIncomeCents,
            cmd.w4DeductionsCents,
            cmd.w4Step2MultipleJobs,
            cmd.w4Version,
            cmd.legacyAllowances,
            cmd.legacyAdditionalWithholdingCents,
            cmd.legacyMaritalStatus,
            cmd.w4EffectiveDate,
            cmd.additionalWithholdingCents,
            cmd.ficaExempt,
            cmd.flsaEnterpriseCovered,
            cmd.flsaExemptStatus.name,
            cmd.isTippedEmployee,
        )
    }

    fun findProfileAsOf(employerId: String, employeeId: String, asOf: LocalDate): EmployeeProfileRow? {
        val rows = jdbcTemplate.query(
            """
            SELECT *
            FROM employee_profile_effective
            WHERE employer_id = ?
              AND employee_id = ?
              AND effective_from <= ?
              AND effective_to > ?
              AND system_from <= CURRENT_TIMESTAMP
              AND system_to > CURRENT_TIMESTAMP
            ORDER BY effective_from DESC, system_from DESC
            FETCH FIRST 1 ROW ONLY
            """.trimIndent(),
            { rs, _ ->
                EmployeeProfileRow(
                    employerId = rs.getString("employer_id"),
                    employeeId = rs.getString("employee_id"),
                    effectiveFrom = rs.getDate("effective_from").toLocalDate(),
                    effectiveTo = rs.getDate("effective_to").toLocalDate(),
                    systemFrom = rs.getTimestamp("system_from").toInstant(),
                    systemTo = rs.getTimestamp("system_to").toInstant(),
                    homeState = rs.getString("home_state"),
                    workState = rs.getString("work_state"),
                    workCity = rs.getString("work_city"),
                    filingStatus = rs.getString("filing_status"),
                    employmentType = rs.getString("employment_type"),
                    hireDate = rs.getDate("hire_date")?.toLocalDate(),
                    terminationDate = rs.getDate("termination_date")?.toLocalDate(),
                    dependents = rs.getObject("dependents")?.let { rs.getInt("dependents") },
                    federalWithholdingExempt = rs.getBoolean("federal_withholding_exempt"),
                    isNonresidentAlien = rs.getBoolean("is_nonresident_alien"),
                    w4AnnualCreditCents = rs.getObject("w4_annual_credit_cents")?.let { rs.getLong("w4_annual_credit_cents") },
                    w4OtherIncomeCents = rs.getObject("w4_other_income_cents")?.let { rs.getLong("w4_other_income_cents") },
                    w4DeductionsCents = rs.getObject("w4_deductions_cents")?.let { rs.getLong("w4_deductions_cents") },
                    w4Step2MultipleJobs = rs.getBoolean("w4_step2_multiple_jobs"),
                    w4Version = rs.getString("w4_version"),
                    legacyAllowances = rs.getObject("legacy_allowances")?.let { rs.getInt("legacy_allowances") },
                    legacyAdditionalWithholdingCents = rs.getObject("legacy_additional_withholding_cents")?.let { rs.getLong("legacy_additional_withholding_cents") },
                    legacyMaritalStatus = rs.getString("legacy_marital_status"),
                    w4EffectiveDate = rs.getDate("w4_effective_date")?.toLocalDate(),
                    additionalWithholdingCents = rs.getObject("additional_withholding_cents")?.let { rs.getLong("additional_withholding_cents") },
                    ficaExempt = rs.getBoolean("fica_exempt"),
                    flsaEnterpriseCovered = rs.getBoolean("flsa_enterprise_covered"),
                    flsaExemptStatus = rs.getString("flsa_exempt_status"),
                    isTippedEmployee = rs.getBoolean("is_tipped_employee"),
                )
            },
            employerId,
            employeeId,
            asOf,
            asOf,
        )

        return rows.firstOrNull()
    }

    fun patchProfileEffectiveDated(employerId: String, employeeId: String, patch: EmployeeProfilePatch): EmployeeProfileRow {
        val current = findProfileAsOf(employerId, employeeId, patch.effectiveFrom)
            ?: error("No employee profile found for $employerId/$employeeId asOf=${patch.effectiveFrom}")

        // Bitemporal rule: never mutate a row in-place.
        // Instead, supersede the current system-time version and insert a new version.
        return if (patch.effectiveFrom == current.effectiveFrom) {
            val updated = merge(current, patch)

            supersedeEmployeeProfile(current)
            insertEmployeeProfileVersion(updated)

            updated
        } else {
            // Prevent overlaps at the requested boundary in the *current* system time.
            val existingAtBoundary = jdbcTemplate.query(
                """
                SELECT effective_from
                FROM employee_profile_effective
                WHERE employer_id = ? AND employee_id = ? AND effective_from = ?
                  AND system_from <= CURRENT_TIMESTAMP
                  AND system_to > CURRENT_TIMESTAMP
                """.trimIndent(),
                { rs, _ -> rs.getDate("effective_from").toLocalDate() },
                employerId,
                employeeId,
                patch.effectiveFrom,
            ).firstOrNull()

            require(existingAtBoundary == null) {
                "Profile segment already exists at effective_from=${patch.effectiveFrom}"
            }

            // Close the current segment by creating a new system-time version with effective_to truncated.
            val closed = current.copy(effectiveTo = patch.effectiveFrom)
            supersedeEmployeeProfile(current)
            insertEmployeeProfileVersion(closed)

            // Insert the new segment for the remainder of the original interval.
            val updated = merge(
                current.copy(
                    effectiveFrom = patch.effectiveFrom,
                    effectiveTo = current.effectiveTo,
                ),
                patch,
            )
            insertEmployeeProfileVersion(updated)

            updated
        }
    }

    private fun supersedeEmployeeProfile(row: EmployeeProfileRow) {
        jdbcTemplate.update(
            """
            UPDATE employee_profile_effective
            SET system_to = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND employee_id = ?
              AND effective_from = ?
              AND system_from = ?
              AND system_to > CURRENT_TIMESTAMP
            """.trimIndent(),
            row.employerId,
            row.employeeId,
            row.effectiveFrom,
            Timestamp.from(row.systemFrom),
        )
    }

    private fun insertEmployeeProfileVersion(row: EmployeeProfileRow) {
        jdbcTemplate.update(
            """
            INSERT INTO employee_profile_effective (
                employer_id,
                employee_id,
                effective_from,
                effective_to,
                home_state,
                work_state,
                work_city,
                filing_status,
                employment_type,
                hire_date,
                termination_date,
                dependents,
                federal_withholding_exempt,
                is_nonresident_alien,
                w4_annual_credit_cents,
                w4_other_income_cents,
                w4_deductions_cents,
                w4_step2_multiple_jobs,
                w4_version,
                legacy_allowances,
                legacy_additional_withholding_cents,
                legacy_marital_status,
                w4_effective_date,
                additional_withholding_cents,
                fica_exempt,
                flsa_enterprise_covered,
                flsa_exempt_status,
                is_tipped_employee
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            row.employerId,
            row.employeeId,
            row.effectiveFrom,
            row.effectiveTo,
            row.homeState,
            row.workState,
            row.workCity,
            row.filingStatus,
            row.employmentType,
            row.hireDate,
            row.terminationDate,
            row.dependents,
            row.federalWithholdingExempt,
            row.isNonresidentAlien,
            row.w4AnnualCreditCents,
            row.w4OtherIncomeCents,
            row.w4DeductionsCents,
            row.w4Step2MultipleJobs,
            row.w4Version,
            row.legacyAllowances,
            row.legacyAdditionalWithholdingCents,
            row.legacyMaritalStatus,
            row.w4EffectiveDate,
            row.additionalWithholdingCents,
            row.ficaExempt,
            row.flsaEnterpriseCovered,
            row.flsaExemptStatus,
            row.isTippedEmployee,
        )
    }

    private fun merge(current: EmployeeProfileRow, patch: EmployeeProfilePatch): EmployeeProfileRow = current.copy(
        homeState = patch.homeState ?: current.homeState,
        workState = patch.workState ?: current.workState,
        workCity = patch.workCity ?: current.workCity,
        filingStatus = (patch.filingStatus ?: FilingStatus.valueOf(current.filingStatus)).name,
        employmentType = (patch.employmentType ?: EmploymentType.valueOf(current.employmentType)).name,
        hireDate = patch.hireDate ?: current.hireDate,
        terminationDate = patch.terminationDate ?: current.terminationDate,
        dependents = patch.dependents ?: current.dependents,
        federalWithholdingExempt = patch.federalWithholdingExempt ?: current.federalWithholdingExempt,
        isNonresidentAlien = patch.isNonresidentAlien ?: current.isNonresidentAlien,
        w4AnnualCreditCents = patch.w4AnnualCreditCents ?: current.w4AnnualCreditCents,
        w4OtherIncomeCents = patch.w4OtherIncomeCents ?: current.w4OtherIncomeCents,
        w4DeductionsCents = patch.w4DeductionsCents ?: current.w4DeductionsCents,
        w4Step2MultipleJobs = patch.w4Step2MultipleJobs ?: current.w4Step2MultipleJobs,
        w4Version = patch.w4Version ?: current.w4Version,
        legacyAllowances = patch.legacyAllowances ?: current.legacyAllowances,
        legacyAdditionalWithholdingCents = patch.legacyAdditionalWithholdingCents ?: current.legacyAdditionalWithholdingCents,
        legacyMaritalStatus = patch.legacyMaritalStatus ?: current.legacyMaritalStatus,
        w4EffectiveDate = patch.w4EffectiveDate ?: current.w4EffectiveDate,
        additionalWithholdingCents = patch.additionalWithholdingCents ?: current.additionalWithholdingCents,
        ficaExempt = patch.ficaExempt ?: current.ficaExempt,
        flsaEnterpriseCovered = patch.flsaEnterpriseCovered ?: current.flsaEnterpriseCovered,
        flsaExemptStatus = (patch.flsaExemptStatus ?: FlsaExemptStatus.valueOf(current.flsaExemptStatus)).name,
        isTippedEmployee = patch.isTippedEmployee ?: current.isTippedEmployee,
    )

    data class CompensationCreate(
        val employerId: String,
        val employeeId: String,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate = LocalDate.of(9999, 12, 31),
        val compensationType: String, // SALARIED or HOURLY
        val annualSalaryCents: Long?,
        val hourlyRateCents: Long?,
        val payFrequency: PayFrequency,
    )

    fun upsertCompensationEffectiveDated(cmd: CompensationCreate) {
        // Find current system-time version overlapping the new effective_from.
        val existing = jdbcTemplate.query(
            """
            SELECT id, effective_from, effective_to
            FROM employment_compensation
            WHERE employer_id = ? AND employee_id = ?
              AND effective_from <= ? AND effective_to > ?
              AND system_from <= CURRENT_TIMESTAMP
              AND system_to > CURRENT_TIMESTAMP
            ORDER BY effective_from DESC, system_from DESC
            FETCH FIRST 1 ROW ONLY
            """.trimIndent(),
            { rs, _ ->
                Triple(
                    rs.getLong("id"),
                    rs.getDate("effective_from").toLocalDate(),
                    rs.getDate("effective_to").toLocalDate(),
                )
            },
            cmd.employerId,
            cmd.employeeId,
            cmd.effectiveFrom,
            cmd.effectiveFrom,
        ).firstOrNull()

        if (existing != null) {
            val (id, from, to) = existing

            // Supersede the existing system-time version.
            jdbcTemplate.update(
                """
                UPDATE employment_compensation
                SET system_to = CURRENT_TIMESTAMP
                WHERE id = ? AND system_to > CURRENT_TIMESTAMP
                """.trimIndent(),
                id,
            )

            if (from != cmd.effectiveFrom) {
                // Reinsert a "closed" version of the prior segment (effective_to truncated).
                jdbcTemplate.update(
                    """
                    INSERT INTO employment_compensation (
                        employer_id,
                        employee_id,
                        effective_from,
                        effective_to,
                        compensation_type,
                        annual_salary_cents,
                        hourly_rate_cents,
                        pay_frequency
                    )
                    SELECT employer_id, employee_id, effective_from, ?, compensation_type, annual_salary_cents, hourly_rate_cents, pay_frequency
                    FROM employment_compensation
                    WHERE id = ?
                    """.trimIndent(),
                    cmd.effectiveFrom,
                    id,
                )
            } else {
                // Boundary correction: nothing to close in valid time; proceed with inserting the new version.
            }

            // Insert the new segment.
            jdbcTemplate.update(
                """
                INSERT INTO employment_compensation (
                    employer_id,
                    employee_id,
                    effective_from,
                    effective_to,
                    compensation_type,
                    annual_salary_cents,
                    hourly_rate_cents,
                    pay_frequency
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                cmd.employerId,
                cmd.employeeId,
                cmd.effectiveFrom,
                if (from == cmd.effectiveFrom) cmd.effectiveTo else to,
                cmd.compensationType,
                cmd.annualSalaryCents,
                cmd.hourlyRateCents,
                cmd.payFrequency.name,
            )

            return
        }

        // No overlap: insert new segment.
        jdbcTemplate.update(
            """
            INSERT INTO employment_compensation (
                employer_id,
                employee_id,
                effective_from,
                effective_to,
                compensation_type,
                annual_salary_cents,
                hourly_rate_cents,
                pay_frequency
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            cmd.employerId,
            cmd.employeeId,
            cmd.effectiveFrom,
            cmd.effectiveTo,
            cmd.compensationType,
            cmd.annualSalaryCents,
            cmd.hourlyRateCents,
            cmd.payFrequency.name,
        )
    }
}

package com.example.usbilling.hr.payperiod

import com.example.usbilling.payroll.model.PayFrequency
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class HrPayScheduleRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    data class PayScheduleUpsert(
        val employerId: String,
        val scheduleId: String,
        val frequency: PayFrequency,
        val firstStartDate: LocalDate,
        val checkDateOffsetDays: Int,
        val semiMonthlyFirstEndDay: Int? = null,
    )

    data class PayScheduleRow(
        val employerId: String,
        val scheduleId: String,
        val frequency: String,
        val firstStartDate: LocalDate,
        val checkDateOffsetDays: Int,
        val semiMonthlyFirstEndDay: Int?,
    )

    fun find(employerId: String, scheduleId: String): PayScheduleRow? {
        val rows = jdbcTemplate.query(
            """
            SELECT employer_id, id, frequency, first_start_date, check_date_offset_days, semi_monthly_first_end_day
            FROM pay_schedule
            WHERE employer_id = ? AND id = ?
            """.trimIndent(),
            { rs, _ ->
                PayScheduleRow(
                    employerId = rs.getString("employer_id"),
                    scheduleId = rs.getString("id"),
                    frequency = rs.getString("frequency"),
                    firstStartDate = rs.getDate("first_start_date").toLocalDate(),
                    checkDateOffsetDays = rs.getInt("check_date_offset_days"),
                    semiMonthlyFirstEndDay = rs.getObject("semi_monthly_first_end_day")?.let { rs.getInt("semi_monthly_first_end_day") },
                )
            },
            employerId,
            scheduleId,
        )

        return rows.firstOrNull()
    }

    fun upsert(cmd: PayScheduleUpsert) {
        val updated = jdbcTemplate.update(
            """
            UPDATE pay_schedule
            SET frequency = ?,
                first_start_date = ?,
                check_date_offset_days = ?,
                semi_monthly_first_end_day = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE employer_id = ? AND id = ?
            """.trimIndent(),
            cmd.frequency.name,
            cmd.firstStartDate,
            cmd.checkDateOffsetDays,
            cmd.semiMonthlyFirstEndDay,
            cmd.employerId,
            cmd.scheduleId,
        )

        if (updated == 0) {
            jdbcTemplate.update(
                """
                INSERT INTO pay_schedule (
                    employer_id, id,
                    frequency,
                    first_start_date,
                    check_date_offset_days,
                    semi_monthly_first_end_day
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                cmd.employerId,
                cmd.scheduleId,
                cmd.frequency.name,
                cmd.firstStartDate,
                cmd.checkDateOffsetDays,
                cmd.semiMonthlyFirstEndDay,
            )
        }
    }
}

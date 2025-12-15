package com.example.uspayroll.hr.payperiod

import com.example.uspayroll.payroll.model.PayFrequency
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class HrPayPeriodRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    data class PayPeriodCreate(
        val employerId: String,
        val payPeriodId: String,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val checkDate: LocalDate,
        val frequency: PayFrequency,
        val sequenceInYear: Int?,
    )

    data class PayPeriodRow(
        val employerId: String,
        val payPeriodId: String,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val checkDate: LocalDate,
        val frequency: String,
        val sequenceInYear: Int?,
    )

    fun find(employerId: String, payPeriodId: String): PayPeriodRow? {
        val rows = jdbcTemplate.query(
            """
            SELECT employer_id, id, start_date, end_date, check_date, frequency, sequence_in_year
            FROM pay_period
            WHERE employer_id = ? AND id = ?
            """.trimIndent(),
            { rs, _ ->
                PayPeriodRow(
                    employerId = rs.getString("employer_id"),
                    payPeriodId = rs.getString("id"),
                    startDate = rs.getDate("start_date").toLocalDate(),
                    endDate = rs.getDate("end_date").toLocalDate(),
                    checkDate = rs.getDate("check_date").toLocalDate(),
                    frequency = rs.getString("frequency"),
                    sequenceInYear = rs.getObject("sequence_in_year")?.let { rs.getInt("sequence_in_year") },
                )
            },
            employerId,
            payPeriodId,
        )
        return rows.firstOrNull()
    }

    fun findByCheckDate(employerId: String, checkDate: LocalDate): PayPeriodRow? {
        val rows = jdbcTemplate.query(
            """
            SELECT employer_id, id, start_date, end_date, check_date, frequency, sequence_in_year
            FROM pay_period
            WHERE employer_id = ? AND check_date = ?
            ORDER BY id
            FETCH FIRST 1 ROW ONLY
            """.trimIndent(),
            { rs, _ ->
                PayPeriodRow(
                    employerId = rs.getString("employer_id"),
                    payPeriodId = rs.getString("id"),
                    startDate = rs.getDate("start_date").toLocalDate(),
                    endDate = rs.getDate("end_date").toLocalDate(),
                    checkDate = rs.getDate("check_date").toLocalDate(),
                    frequency = rs.getString("frequency"),
                    sequenceInYear = rs.getObject("sequence_in_year")?.let { rs.getInt("sequence_in_year") },
                )
            },
            employerId,
            checkDate,
        )
        return rows.firstOrNull()
    }

    fun findPreviousByEndDate(employerId: String, frequency: PayFrequency, startDateExclusive: LocalDate): PayPeriodRow? {
        val rows = jdbcTemplate.query(
            """
            SELECT employer_id, id, start_date, end_date, check_date, frequency, sequence_in_year
            FROM pay_period
            WHERE employer_id = ?
              AND frequency = ?
              AND end_date < ?
            ORDER BY end_date DESC, id DESC
            FETCH FIRST 1 ROW ONLY
            """.trimIndent(),
            { rs, _ ->
                PayPeriodRow(
                    employerId = rs.getString("employer_id"),
                    payPeriodId = rs.getString("id"),
                    startDate = rs.getDate("start_date").toLocalDate(),
                    endDate = rs.getDate("end_date").toLocalDate(),
                    checkDate = rs.getDate("check_date").toLocalDate(),
                    frequency = rs.getString("frequency"),
                    sequenceInYear = rs.getObject("sequence_in_year")?.let { rs.getInt("sequence_in_year") },
                )
            },
            employerId,
            frequency.name,
            startDateExclusive,
        )
        return rows.firstOrNull()
    }

    fun findNextByStartDate(employerId: String, frequency: PayFrequency, endDateExclusive: LocalDate): PayPeriodRow? {
        val rows = jdbcTemplate.query(
            """
            SELECT employer_id, id, start_date, end_date, check_date, frequency, sequence_in_year
            FROM pay_period
            WHERE employer_id = ?
              AND frequency = ?
              AND start_date > ?
            ORDER BY start_date ASC, id ASC
            FETCH FIRST 1 ROW ONLY
            """.trimIndent(),
            { rs, _ ->
                PayPeriodRow(
                    employerId = rs.getString("employer_id"),
                    payPeriodId = rs.getString("id"),
                    startDate = rs.getDate("start_date").toLocalDate(),
                    endDate = rs.getDate("end_date").toLocalDate(),
                    checkDate = rs.getDate("check_date").toLocalDate(),
                    frequency = rs.getString("frequency"),
                    sequenceInYear = rs.getObject("sequence_in_year")?.let { rs.getInt("sequence_in_year") },
                )
            },
            employerId,
            frequency.name,
            endDateExclusive,
        )
        return rows.firstOrNull()
    }

    /**
     * Overlap check for inclusive date ranges [startDate, endDate].
     */
    fun hasOverlappingRange(employerId: String, startDate: LocalDate, endDate: LocalDate): Boolean {
        val count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(1)
            FROM pay_period
            WHERE employer_id = ?
              AND start_date <= ?
              AND end_date >= ?
            """.trimIndent(),
            Long::class.java,
            employerId,
            endDate,
            startDate,
        )
        return (count ?: 0L) > 0L
    }

    fun create(cmd: PayPeriodCreate) {
        jdbcTemplate.update(
            """
            INSERT INTO pay_period (
                employer_id,
                id,
                start_date,
                end_date,
                check_date,
                frequency,
                sequence_in_year
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            cmd.employerId,
            cmd.payPeriodId,
            cmd.startDate,
            cmd.endDate,
            cmd.checkDate,
            cmd.frequency.name,
            cmd.sequenceInYear,
        )
    }
}

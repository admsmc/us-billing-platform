package com.example.uspayroll.hr.garnishment

import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals

/**
 * Focused unit test for [GarnishmentReconciliationService] that exercises
 * multi-period arrears reduction behavior using the existing ledger tables.
 */
class GarnishmentReconciliationServiceTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var service: GarnishmentReconciliationService

    private val employerId = EmployerId("EMP-RECON-TEST")
    private val employeeId = EmployeeId("EE-RECON-TEST")

    @BeforeEach
    fun setUp() {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:hr_recon;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }

        jdbcTemplate = JdbcTemplate(dataSource)
        service = GarnishmentReconciliationService(jdbcTemplate)

        // Create minimal schema for garnishment_order and garnishment_ledger.
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS garnishment_order (
              employer_id            VARCHAR(64)  NOT NULL,
              employee_id            VARCHAR(64)  NOT NULL,
              order_id               VARCHAR(128) NOT NULL,
              type                   VARCHAR(64)  NOT NULL,
              issuing_jurisdiction_type VARCHAR(16),
              issuing_jurisdiction_code VARCHAR(16),
              case_number            VARCHAR(128),
              status                 VARCHAR(32)  NOT NULL,
              served_date            DATE,
              end_date               DATE,
              priority_class         INT          NOT NULL,
              sequence_within_class  INT          NOT NULL,
              initial_arrears_cents  BIGINT,
              current_arrears_cents  BIGINT,
              supports_other_dependents BOOLEAN,
              arrears_at_least_12_weeks BOOLEAN,
              created_at             TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
              updated_at             TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (employer_id, employee_id, order_id)
            )
            """.trimIndent(),
        )

        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS garnishment_ledger (
              employer_id             VARCHAR(64)  NOT NULL,
              employee_id             VARCHAR(64)  NOT NULL,
              order_id                VARCHAR(128) NOT NULL,
              total_withheld_cents    BIGINT       NOT NULL,
              initial_arrears_cents   BIGINT,
              remaining_arrears_cents BIGINT,
              last_check_date         DATE,
              last_paycheck_id        VARCHAR(128),
              last_pay_run_id         VARCHAR(128),
              created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
              updated_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (employer_id, employee_id, order_id)
            )
            """.trimIndent(),
        )

        // Clean out any existing rows between tests.
        jdbcTemplate.update("DELETE FROM garnishment_ledger")
        jdbcTemplate.update("DELETE FROM garnishment_order")
    }

    @Test
    fun `multi-period ledger reconciliation reduces arrears and flips 12-week flag`() {
        val orderId = "ORDER-ARREARS-LEDGER"

        // Seed an order with 5,000 of initial arrears, served on 2024-01-01.
        jdbcTemplate.update(
            """
            INSERT INTO garnishment_order (
              employer_id, employee_id, order_id,
              type, issuing_jurisdiction_type, issuing_jurisdiction_code,
              case_number, status,
              served_date, end_date,
              priority_class, sequence_within_class,
              initial_arrears_cents, current_arrears_cents,
              supports_other_dependents, arrears_at_least_12_weeks
            ) VALUES (?, ?, ?, 'CHILD_SUPPORT', 'STATE', 'MI', ?, 'ACTIVE', ?, NULL, 0, 0, ?, NULL, NULL, FALSE)
            """.trimIndent(),
            employerId.value,
            employeeId.value,
            orderId,
            "CASE-ARREARS-LEDGER",
            java.sql.Date.valueOf("2024-01-01"),
            5_000_00L,
        )

        // Period 1: as of 2024-05-01 (< 12 weeks from served date), withhold 2,000.
        jdbcTemplate.update(
            """
            INSERT INTO garnishment_ledger (
              employer_id, employee_id, order_id,
              total_withheld_cents, initial_arrears_cents, remaining_arrears_cents,
              last_check_date, last_paycheck_id, last_pay_run_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            employerId.value,
            employeeId.value,
            orderId,
            2_000_00L,
            5_000_00L,
            5_000_00L,
            java.sql.Date.valueOf("2024-05-01"),
            null,
            null,
        )

        service.reconcileForEmployee(employerId, employeeId)

        val afterPeriod1 = jdbcTemplate.queryForMap(
            "SELECT current_arrears_cents, arrears_at_least_12_weeks, status FROM garnishment_order WHERE employer_id = ? AND employee_id = ? AND order_id = ?",
            employerId.value,
            employeeId.value,
            orderId,
        )

        assertEquals(3_000_00L, (afterPeriod1["CURRENT_ARREARS_CENTS"] as Number).toLong())
        // Less than 12 weeks of arrears age at this point.
        assertEquals(false, afterPeriod1["ARREARS_AT_LEAST_12_WEEKS"] as Boolean)
        assertEquals("ACTIVE", afterPeriod1["STATUS"] as String)

        // Period 2: as of 2025-01-15 (> 12 weeks from served date), total_withheld grows to 4,000.
        jdbcTemplate.update(
            "UPDATE garnishment_ledger SET total_withheld_cents = ?, remaining_arrears_cents = ?, last_check_date = ? WHERE employer_id = ? AND employee_id = ? AND order_id = ?",
            4_000_00L,
            3_000_00L,
            java.sql.Date.valueOf("2025-01-15"),
            employerId.value,
            employeeId.value,
            orderId,
        )

        service.reconcileForEmployee(employerId, employeeId)

        val afterPeriod2 = jdbcTemplate.queryForMap(
            "SELECT current_arrears_cents, arrears_at_least_12_weeks, status FROM garnishment_order WHERE employer_id = ? AND employee_id = ? AND order_id = ?",
            employerId.value,
            employeeId.value,
            orderId,
        )

        // Remaining arrears = 5,000 - 4,000 = 1,000.
        assertEquals(1_000_00L, (afterPeriod2["CURRENT_ARREARS_CENTS"] as Number).toLong())
        // Age of arrears now exceeds 12 weeks, flag should be true.
        assertEquals(true, afterPeriod2["ARREARS_AT_LEAST_12_WEEKS"] as Boolean)
        assertEquals("ACTIVE", afterPeriod2["STATUS"] as String)

        // Period 3: full payoff, total_withheld == initial_arrears.
        jdbcTemplate.update(
            "UPDATE garnishment_ledger SET total_withheld_cents = ?, remaining_arrears_cents = ?, last_check_date = ? WHERE employer_id = ? AND employee_id = ? AND order_id = ?",
            5_000_00L,
            0L,
            java.sql.Date.valueOf("2025-03-01"),
            employerId.value,
            employeeId.value,
            orderId,
        )

        service.reconcileForEmployee(employerId, employeeId)

        val afterPeriod3 = jdbcTemplate.queryForMap(
            "SELECT current_arrears_cents, arrears_at_least_12_weeks, status FROM garnishment_order WHERE employer_id = ? AND employee_id = ? AND order_id = ?",
            employerId.value,
            employeeId.value,
            orderId,
        )

        // Arrears fully paid; remaining is zero and order is completed; the
        // 12-week flag is no longer relevant once arrears are gone.
        assertEquals(0L, (afterPeriod3["CURRENT_ARREARS_CENTS"] as Number).toLong())
        assertEquals(false, afterPeriod3["ARREARS_AT_LEAST_12_WEEKS"] as Boolean)
        assertEquals("COMPLETED", afterPeriod3["STATUS"] as String)
    }
}

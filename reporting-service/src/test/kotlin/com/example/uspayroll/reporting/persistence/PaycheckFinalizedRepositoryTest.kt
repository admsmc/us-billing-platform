package com.example.uspayroll.reporting.persistence

import com.example.uspayroll.messaging.events.reporting.PaycheckLedgerAction
import com.example.uspayroll.messaging.events.reporting.PaycheckLedgerEvent
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

class PaycheckFinalizedRepositoryTest {

    private fun newDataSource(dbName: String): JdbcDataSource {
        return JdbcDataSource().apply {
            setURL("jdbc:h2:mem:$dbName;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
    }

    private fun initSchema(ds: JdbcDataSource) {
        val ddl = this::class.java.classLoader
            .getResourceAsStream("db/migration/V004__create_paycheck_finalized.sql")
            ?.bufferedReader()
            ?.readText()
            ?: error("missing V004__create_paycheck_finalized.sql resource")

        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                ddl.split(";")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { stmt -> st.execute(stmt) }
            }
        }
    }

    @Test
    fun `upsert keeps latest occurredAt and ledger enrich fills check_date`() {
        val ds = newDataSource("reporting_paycheck_finalized_repo")
        initSchema(ds)

        val repo = PaycheckFinalizedRepository(JdbcTemplate(ds))

        val base = PaycheckFinalizedRepository.PaycheckFinalizedProjectionEvent(
            eventId = "evt-1",
            occurredAt = Instant.parse("2025-01-15T00:00:00Z"),
            employerId = "emp-1",
            payRunId = "run-1",
            paycheckId = "chk-1",
            employeeId = "ee-1",
        )

        repo.upsertFromEvent(base)
        repo.upsertFromEvent(base.copy(eventId = "evt-older", occurredAt = Instant.parse("2025-01-14T00:00:00Z"), employeeId = "nope"))

        val employeeAfterOld = JdbcTemplate(ds).queryForObject(
            "SELECT employee_id FROM paycheck_finalized WHERE employer_id = ? AND paycheck_id = ?",
            String::class.java,
            "emp-1",
            "chk-1",
        )
        assertEquals("ee-1", employeeAfterOld)

        val ledger = PaycheckLedgerEvent(
            eventId = "evt-ledger-1",
            occurredAt = Instant.parse("2025-01-16T00:00:00Z"),
            action = PaycheckLedgerAction.COMMITTED,
            employerId = "emp-1",
            employeeId = "ee-1",
            payRunId = "run-1",
            payRunType = "REGULAR",
            runSequence = 1,
            payPeriodId = "pp-1",
            paycheckId = "chk-1",
            periodStartIso = "2025-01-01",
            periodEndIso = "2025-01-14",
            checkDateIso = "2025-01-15",
            currency = "USD",
            grossCents = 100,
            netCents = 80,
        )

        repo.enrichFromLedgerEvent(ledger)

        val checkDate = JdbcTemplate(ds).queryForObject(
            "SELECT check_date FROM paycheck_finalized WHERE employer_id = ? AND paycheck_id = ?",
            java.sql.Date::class.java,
            "emp-1",
            "chk-1",
        )
        assertEquals(java.sql.Date.valueOf("2025-01-15"), checkDate)
    }
}

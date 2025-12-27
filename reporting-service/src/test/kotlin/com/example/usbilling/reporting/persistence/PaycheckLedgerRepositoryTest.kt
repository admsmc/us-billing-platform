package com.example.usbilling.reporting.persistence

import com.example.usbilling.messaging.events.reporting.PaycheckLedgerAction
import com.example.usbilling.messaging.events.reporting.PaycheckLedgerEvent
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

class PaycheckLedgerRepositoryTest {

    private fun newDataSource(dbName: String): JdbcDataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:$dbName;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
        user = "sa"
        password = ""
    }

    private fun initLedgerSchema(ds: JdbcDataSource) {
        val ddl = this::class.java.classLoader
            .getResourceAsStream("db/migration/V002__create_paycheck_ledger_entry.sql")
            ?.bufferedReader()
            ?.readText()
            ?: error("missing V002__create_paycheck_ledger_entry.sql resource")

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
    fun `upsertFromEvent inserts and updates by occurredAt`() {
        val ds = newDataSource("reporting_paycheck_ledger_repo")
        initLedgerSchema(ds)

        val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        val repo = PaycheckLedgerRepository(JdbcTemplate(ds), mapper)

        val base = PaycheckLedgerEvent(
            eventId = "evt-1",
            occurredAt = Instant.parse("2025-01-15T00:00:00Z"),
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
            grossCents = 100_00L,
            netCents = 80_00L,
        )

        repo.upsertFromEvent(base)

        val count = JdbcTemplate(ds).queryForObject(
            "SELECT COUNT(*) FROM paycheck_ledger_entry WHERE employer_id = ? AND paycheck_id = ?",
            Long::class.java,
            "emp-1",
            "chk-1",
        )
        assertEquals(1L, count)

        // Older event should not overwrite.
        repo.upsertFromEvent(base.copy(eventId = "evt-older", occurredAt = Instant.parse("2025-01-14T00:00:00Z"), netCents = 1L))

        val netAfterOld = JdbcTemplate(ds).queryForObject(
            "SELECT net_cents FROM paycheck_ledger_entry WHERE employer_id = ? AND paycheck_id = ?",
            Long::class.java,
            "emp-1",
            "chk-1",
        )
        assertEquals(80_00L, netAfterOld)

        // Newer event should overwrite.
        repo.upsertFromEvent(base.copy(eventId = "evt-newer", occurredAt = Instant.parse("2025-01-16T00:00:00Z"), netCents = 81_00L))

        val netAfterNew = JdbcTemplate(ds).queryForObject(
            "SELECT net_cents FROM paycheck_ledger_entry WHERE employer_id = ? AND paycheck_id = ?",
            Long::class.java,
            "emp-1",
            "chk-1",
        )
        assertEquals(81_00L, netAfterNew)
    }
}

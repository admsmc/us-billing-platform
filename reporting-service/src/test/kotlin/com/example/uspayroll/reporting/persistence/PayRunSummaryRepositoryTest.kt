package com.example.uspayroll.reporting.persistence

import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

class PayRunSummaryRepositoryTest {

    private fun newDataSource(dbName: String): JdbcDataSource {
        return JdbcDataSource().apply {
            setURL("jdbc:h2:mem:$dbName;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
    }

    private fun initSchema(ds: JdbcDataSource) {
        val ddl = this::class.java.classLoader
            .getResourceAsStream("db/migration/V003__create_pay_run_summary.sql")
            ?.bufferedReader()
            ?.readText()
            ?: error("missing V003__create_pay_run_summary.sql resource")

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
    fun `upsert keeps latest occurredAt`() {
        val ds = newDataSource("reporting_pay_run_summary_repo")
        initSchema(ds)

        val repo = PayRunSummaryRepository(JdbcTemplate(ds))

        val base = PayRunSummaryRepository.PayRunFinalizedProjectionEvent(
            eventId = "evt-1",
            occurredAt = Instant.parse("2025-01-15T00:00:00Z"),
            employerId = "emp-1",
            payRunId = "run-1",
            payPeriodId = "pp-1",
            status = "FINALIZED",
            total = 10,
            succeeded = 9,
            failed = 1,
        )

        repo.upsertFromEvent(base)

        repo.upsertFromEvent(base.copy(eventId = "evt-older", occurredAt = Instant.parse("2025-01-14T00:00:00Z"), failed = 999))

        val failedAfterOld = JdbcTemplate(ds).queryForObject(
            "SELECT failed FROM pay_run_summary WHERE employer_id = ? AND pay_run_id = ?",
            Int::class.java,
            "emp-1",
            "run-1",
        )
        assertEquals(1, failedAfterOld)

        repo.upsertFromEvent(base.copy(eventId = "evt-newer", occurredAt = Instant.parse("2025-01-16T00:00:00Z"), failed = 2))

        val failedAfterNew = JdbcTemplate(ds).queryForObject(
            "SELECT failed FROM pay_run_summary WHERE employer_id = ? AND pay_run_id = ?",
            Int::class.java,
            "emp-1",
            "run-1",
        )
        assertEquals(2, failedAfterNew)
    }
}

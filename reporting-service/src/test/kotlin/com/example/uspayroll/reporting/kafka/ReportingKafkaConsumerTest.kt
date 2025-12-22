package com.example.uspayroll.reporting.kafka

import com.example.uspayroll.messaging.events.reporting.PaycheckLedgerAction
import com.example.uspayroll.messaging.events.reporting.PaycheckLedgerEvent
import com.example.uspayroll.messaging.inbox.EventInboxSchema
import com.example.uspayroll.messaging.inbox.JdbcEventInbox
import com.example.uspayroll.reporting.persistence.PayRunSummaryRepository
import com.example.uspayroll.reporting.persistence.PaycheckFinalizedRepository
import com.example.uspayroll.reporting.persistence.PaycheckLedgerRepository
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

class ReportingKafkaConsumerTest {

    private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    private fun newDataSource(dbName: String): JdbcDataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:$dbName;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
        user = "sa"
        password = ""
    }

    private fun initDb(ds: JdbcDataSource): JdbcEventInbox {
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.execute(EventInboxSchema.ddl)

                val migrations = listOf(
                    "db/migration/V002__create_paycheck_ledger_entry.sql",
                    "db/migration/V003__create_pay_run_summary.sql",
                    "db/migration/V004__create_paycheck_finalized.sql",
                )

                migrations.forEach { path ->
                    val ddl = this::class.java.classLoader
                        .getResourceAsStream(path)
                        ?.bufferedReader()
                        ?.readText()
                        ?: error("missing $path resource")

                    ddl.split(";")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .forEach { stmt -> st.execute(stmt) }
                }
            }
        }
        return JdbcEventInbox(ds)
    }

    private fun countInboxRows(ds: JdbcDataSource): Long {
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM event_inbox").use { rs ->
                    rs.next()
                    return rs.getLong(1)
                }
            }
        }
    }

    private fun record(topic: String, value: String, headers: Map<String, String> = emptyMap()): ConsumerRecord<String, String> {
        val rec = ConsumerRecord(topic, 0, 0L, "key-1", value)
        headers.forEach { (k, v) ->
            rec.headers().add(k, v.toByteArray(Charsets.UTF_8))
        }
        return rec
    }

    private fun newConsumer(props: ReportingKafkaProperties, ds: JdbcDataSource, inbox: JdbcEventInbox): ReportingKafkaConsumer {
        val jdbc = JdbcTemplate(ds)
        val ledgerRepo = PaycheckLedgerRepository(jdbc, mapper)
        val payRunRepo = PayRunSummaryRepository(jdbc)
        val paycheckFinalizedRepo = PaycheckFinalizedRepository(jdbc)
        val meterRegistry = SimpleMeterRegistry()
        return ReportingKafkaConsumer(props, mapper, inbox, ledgerRepo, payRunRepo, paycheckFinalizedRepo, meterRegistry)
    }

    @Test
    fun `payrun finalized events are inbox-idempotent and materialize pay_run_summary`() {
        val ds = newDataSource("reporting_kafka_consumer_payrun")
        val inbox = initDb(ds)

        val props = ReportingKafkaProperties(enabled = true, consumerName = "reporting-service")
        val consumer = newConsumer(props, ds, inbox)

        val rec = record(
            topic = "payrun.finalized",
            value = """{"eventId":"evt-1","occurredAt":"2025-01-15T00:00:00Z","employerId":"emp-1","payRunId":"run-1","payPeriodId":"pp-1","status":"FINALIZED","total":10,"succeeded":9,"failed":1}""",
            headers = mapOf("X-Event-Id" to "evt-1", "X-Event-Type" to "PayRunFinalized"),
        )

        consumer.onPayRunFinalized(rec)
        assertEquals(1L, countInboxRows(ds))

        val payRunCount = JdbcTemplate(ds).queryForObject(
            "SELECT COUNT(*) FROM pay_run_summary WHERE employer_id = ? AND pay_run_id = ?",
            Long::class.java,
            "emp-1",
            "run-1",
        )
        assertEquals(1L, payRunCount)

        consumer.onPayRunFinalized(rec)
        assertEquals(1L, countInboxRows(ds))
    }

    @Test
    fun `paycheck finalized events are inbox-idempotent and materialize paycheck_finalized`() {
        val ds = newDataSource("reporting_kafka_consumer_paycheck")
        val inbox = initDb(ds)

        val props = ReportingKafkaProperties(enabled = true, consumerName = "reporting-service")
        val consumer = newConsumer(props, ds, inbox)

        val rec = record(
            topic = "paycheck.finalized",
            value = """{"eventId":"evt-2","occurredAt":"2025-01-15T00:00:00Z","employerId":"emp-1","payRunId":"run-1","paycheckId":"chk-1","employeeId":"ee-1"}""",
            headers = mapOf("X-Event-Type" to "PaycheckFinalized"),
        )

        consumer.onPaycheckFinalized(rec)
        assertEquals(1L, countInboxRows(ds))

        val paycheckCount = JdbcTemplate(ds).queryForObject(
            "SELECT COUNT(*) FROM paycheck_finalized WHERE employer_id = ? AND paycheck_id = ?",
            Long::class.java,
            "emp-1",
            "chk-1",
        )
        assertEquals(1L, paycheckCount)

        consumer.onPaycheckFinalized(rec)
        assertEquals(1L, countInboxRows(ds))
    }

    @Test
    fun `missing eventId results in no inbox insert`() {
        val ds = newDataSource("reporting_kafka_consumer_missing")
        val inbox = initDb(ds)

        val props = ReportingKafkaProperties(enabled = true, consumerName = "reporting-service")
        val consumer = newConsumer(props, ds, inbox)

        val before = countInboxRows(ds)
        consumer.onPayRunFinalized(record(topic = "payrun.finalized", value = """{"foo":"bar"}"""))
        val after = countInboxRows(ds)

        assertEquals(before, after)
    }

    @Test
    fun `paycheck ledger events are inbox-idempotent and materialize paycheck_ledger_entry`() {
        val ds = newDataSource("reporting_kafka_consumer_ledger")
        val inbox = initDb(ds)

        val props = ReportingKafkaProperties(enabled = true, consumerName = "reporting-service")
        val consumer = newConsumer(props, ds, inbox)

        val event = PaycheckLedgerEvent(
            eventId = "evt-ledger-1",
            occurredAt = Instant.parse("2025-01-15T00:00:00Z"),
            action = PaycheckLedgerAction.COMMITTED,
            employerId = "emp-1",
            employeeId = "ee-1",
            payRunId = "run-1",
            payRunType = "REGULAR",
            runSequence = 1,
            payPeriodId = "2025-01-BW1",
            paycheckId = "chk-1",
            periodStartIso = "2025-01-01",
            periodEndIso = "2025-01-14",
            checkDateIso = "2025-01-15",
            currency = "USD",
            grossCents = 100_00L,
            netCents = 80_00L,
        )

        val rec = record(
            topic = "paycheck.ledger",
            value = mapper.writeValueAsString(event),
            headers = mapOf("X-Event-Id" to "evt-ledger-1", "X-Event-Type" to "PaycheckLedger"),
        )

        consumer.onPaycheckLedger(rec)
        assertEquals(1L, countInboxRows(ds))

        val rowCount = JdbcTemplate(ds).queryForObject(
            "SELECT COUNT(*) FROM paycheck_ledger_entry WHERE employer_id = ? AND paycheck_id = ?",
            Long::class.java,
            "emp-1",
            "chk-1",
        )
        assertTrue((rowCount ?: 0L) == 1L)

        consumer.onPaycheckLedger(rec)
        assertEquals(1L, countInboxRows(ds))
    }
}

package com.example.uspayroll.reporting.kafka

import com.example.uspayroll.messaging.inbox.EventInboxSchema
import com.example.uspayroll.messaging.inbox.JdbcEventInbox
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReportingKafkaConsumerTest {

    private fun newDataSource(dbName: String): JdbcDataSource {
        return JdbcDataSource().apply {
            setURL("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
    }

    private fun initInbox(ds: JdbcDataSource): JdbcEventInbox {
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.execute(EventInboxSchema.ddl)
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

    @Test
    fun `eventId is derived from kafka header when present`() {
        val ds = newDataSource("reporting_kafka_consumer_header")
        val inbox = initInbox(ds)

        val props = ReportingKafkaProperties(enabled = true, consumerName = "reporting-service")
        val consumer = ReportingKafkaConsumer(props, jacksonObjectMapper(), inbox)

        val rec = record(
            topic = "payrun.finalized",
            value = """{"eventType":"PayRunFinalized"}""",
            headers = mapOf("X-Event-Id" to "evt-1"),
        )

        consumer.onPayRunFinalized(rec)

        // If the consumer inserted the inbox marker, a second attempt should be rejected.
        assertEquals(1L, countInboxRows(ds))
        org.junit.jupiter.api.Assertions.assertFalse(inbox.tryMarkProcessed("reporting-service", "evt-1"))
    }

    @Test
    fun `eventId is derived from JSON payload when header missing`() {
        val ds = newDataSource("reporting_kafka_consumer_payload")
        val inbox = initInbox(ds)

        val props = ReportingKafkaProperties(enabled = true, consumerName = "reporting-service")
        val consumer = ReportingKafkaConsumer(props, jacksonObjectMapper(), inbox)

        val rec = record(
            topic = "paycheck.finalized",
            value = """{"eventId":"evt-2","eventType":"PaycheckFinalized"}""",
        )

        consumer.onPaycheckFinalized(rec)

        assertEquals(1L, countInboxRows(ds))
        org.junit.jupiter.api.Assertions.assertFalse(inbox.tryMarkProcessed("reporting-service", "evt-2"))
    }

    @Test
    fun `missing eventId results in no inbox insert`() {
        val ds = newDataSource("reporting_kafka_consumer_missing")
        initInbox(ds)

        val props = ReportingKafkaProperties(enabled = true, consumerName = "reporting-service")
        val consumer = ReportingKafkaConsumer(props, jacksonObjectMapper(), JdbcEventInbox(ds))

        val before = countInboxRows(ds)
        consumer.onPayRunFinalized(record(topic = "payrun.finalized", value = """{"foo":"bar"}"""))
        val after = countInboxRows(ds)

        assertEquals(before, after)
    }

    @Test
    fun `duplicate events are ignored by inbox idempotency`() {
        val ds = newDataSource("reporting_kafka_consumer_duplicate")
        initInbox(ds)

        val props = ReportingKafkaProperties(enabled = true, consumerName = "reporting-service")
        val consumer = ReportingKafkaConsumer(props, jacksonObjectMapper(), JdbcEventInbox(ds))

        val rec = record(
            topic = "payrun.finalized",
            value = """{"eventType":"PayRunFinalized"}""",
            headers = mapOf("X-Event-Id" to "evt-3"),
        )

        consumer.onPayRunFinalized(rec)
        assertEquals(1L, countInboxRows(ds))

        consumer.onPayRunFinalized(rec)
        assertEquals(1L, countInboxRows(ds))
    }
}

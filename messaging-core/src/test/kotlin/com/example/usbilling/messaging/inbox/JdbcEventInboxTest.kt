package com.example.usbilling.messaging.inbox

import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class JdbcEventInboxTest {

    private fun newInbox(): Pair<JdbcEventInbox, javax.sql.DataSource> {
        val dbName = "inbox_test_${java.util.UUID.randomUUID()}"
        val ds = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }

        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.execute(EventInboxSchema.ddl)
            }
        }

        return JdbcEventInbox(ds) to ds
    }

    @Test
    fun `runIfFirst executes once per consumer and event`() {
        val (inbox, ds) = newInbox()
        val consumer = "c1"
        val eventId = "e1"

        val r1 = inbox.runIfFirst(consumer, eventId) { "ok" }
        val r2 = inbox.runIfFirst(consumer, eventId) { "ok2" }

        assertEquals("ok", r1)
        assertNull(r2)

        val rows = ds.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM event_inbox WHERE consumer = ? AND event_id = ?").use { ps ->
                ps.setString(1, consumer)
                ps.setString(2, eventId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }
        assertEquals(1L, rows)
    }

    @Test
    fun `runIfFirst unmarks on exception so message can be retried`() {
        val (inbox, ds) = newInbox()
        val consumer = "c2"
        val eventId = "e2"

        assertThrows(IllegalStateException::class.java) {
            inbox.runIfFirst(consumer, eventId) {
                error("boom")
            }
        }

        // Because the handler threw, the inbox marker should be removed.
        val afterFail = ds.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM event_inbox WHERE consumer = ? AND event_id = ?").use { ps ->
                ps.setString(1, consumer)
                ps.setString(2, eventId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }
        assertEquals(0L, afterFail)

        // Retry should run the block.
        val r2 = inbox.runIfFirst(consumer, eventId) { "ok" }
        assertEquals("ok", r2)

        val afterSuccess = ds.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM event_inbox WHERE consumer = ? AND event_id = ?").use { ps ->
                ps.setString(1, consumer)
                ps.setString(2, eventId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }
        assertEquals(1L, afterSuccess)
    }
}

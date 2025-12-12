package com.example.uspayroll.reporting.kafka

import com.example.uspayroll.messaging.inbox.JdbcEventInbox
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@TestPropertySource(
    properties = [
        "spring.task.scheduling.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:reporting_event_inbox_it;DB_CLOSE_DELAY=-1",
    ],
)
class EventInboxIT(
    private val inbox: JdbcEventInbox,
) {

    @Test
    fun `tryMarkProcessed is idempotent per consumer`() {
        val consumer = "reporting-service"
        val eventId = "evt-1"

        assertTrue(inbox.tryMarkProcessed(consumer, eventId))
        assertFalse(inbox.tryMarkProcessed(consumer, eventId))

        // Different consumer should be treated independently.
        assertTrue(inbox.tryMarkProcessed("reporting-service-2", eventId))
    }
}

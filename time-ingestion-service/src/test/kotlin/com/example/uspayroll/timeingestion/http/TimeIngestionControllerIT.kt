package com.example.uspayroll.timeingestion.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.RequestEntity
import org.springframework.test.context.TestConstructor
import java.net.URI
import java.time.LocalDate

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class TimeIngestionControllerIT(
    private val rest: TestRestTemplate,
) {

    @Test
    fun `CA example 10h x 5 days returns 40 regular and 10 overtime`() {
        val employerId = "EMP-1"
        val employeeId = "EE-1"

        val start = LocalDate.of(2025, 1, 6) // Monday
        val end = start.plusDays(4)

        // Seed 5 entries.
        (0 until 5).forEach { i ->
            val date = start.plusDays(i.toLong())
            val req = TimeIngestionController.UpsertTimeEntryRequest(
                date = date,
                hours = 10.0,
                worksiteKey = null,
            )
            val res = rest.exchange(
                RequestEntity
                    .put(URI.create("/employers/$employerId/employees/$employeeId/time-entries/$date"))
                    .body(req),
                Map::class.java,
            )
            // First write creates.
            assertEquals(HttpStatus.CREATED, res.statusCode)
        }

        val summary = rest.getForEntity(
            "/employers/$employerId/employees/$employeeId/time-summary?start=$start&end=$end&workState=CA&weekStartsOn=MONDAY",
            TimeIngestionController.TimeSummaryResponse::class.java,
        )

        assertEquals(HttpStatus.OK, summary.statusCode)
        val body = summary.body!!

        assertEquals(40.0, body.totals.regularHours)
        assertEquals(10.0, body.totals.overtimeHours)
        assertEquals(0.0, body.totals.doubleTimeHours)
    }
}

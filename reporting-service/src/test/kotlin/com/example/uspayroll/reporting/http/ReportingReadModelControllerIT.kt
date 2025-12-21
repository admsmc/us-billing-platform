package com.example.uspayroll.reporting.http

import com.example.uspayroll.messaging.events.reporting.PaycheckLedgerAction
import com.example.uspayroll.messaging.events.reporting.PaycheckLedgerEvent
import com.example.uspayroll.reporting.persistence.PaycheckLedgerRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant

@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@TestPropertySource(
    properties = [
        "spring.task.scheduling.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:reporting_read_model_controller_it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "reporting.kafka.enabled=false",
    ],
)
class ReportingReadModelControllerIT(
    private val mockMvc: MockMvc,
    private val ledgerRepository: PaycheckLedgerRepository,
) {

    @Test
    fun `GET paycheck-ledger returns entries in date range`() {
        val evt = PaycheckLedgerEvent(
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
        ledgerRepository.upsertFromEvent(evt)

        val res = mockMvc.get("/employers/{employerId}/reports/paycheck-ledger", "emp-1") {
            param("start", "2025-01-01")
            param("end", "2025-01-31")
            param("includePayload", "true")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.employerId") { value("emp-1") }
            jsonPath("$.count") { value(1) }
            jsonPath("$.entries[0].paycheckId") { value("chk-1") }
            jsonPath("$.entries[0].netCents") { value(8000) }
            jsonPath("$.entries[0].payload.eventId") { value("evt-ledger-1") }
            jsonPath("$.entries[0].payload.paycheckId") { value("chk-1") }
        }.andReturn()

        // sanity: payload returned once
        assertEquals(200, res.response.status)
    }

    @Test
    fun `GET payrun net-totals returns sums by employee`() {
        val evt1 = PaycheckLedgerEvent(
            eventId = "evt-ledger-2",
            occurredAt = Instant.parse("2025-01-15T00:00:00Z"),
            action = PaycheckLedgerAction.COMMITTED,
            employerId = "emp-2",
            employeeId = "ee-1",
            payRunId = "run-9",
            payRunType = "REGULAR",
            runSequence = 1,
            payPeriodId = "pp-1",
            paycheckId = "chk-a",
            periodStartIso = "2025-01-01",
            periodEndIso = "2025-01-14",
            checkDateIso = "2025-01-15",
            currency = "USD",
            grossCents = 100_00L,
            netCents = 80_00L,
        )
        val evt2 = evt1.copy(eventId = "evt-ledger-3", paycheckId = "chk-b", netCents = 70_00L)
        val evtOtherEmployee = evt1.copy(eventId = "evt-ledger-4", employeeId = "ee-2", paycheckId = "chk-c", netCents = 50_00L)

        ledgerRepository.upsertFromEvent(evt1)
        ledgerRepository.upsertFromEvent(evt2)
        ledgerRepository.upsertFromEvent(evtOtherEmployee)

        mockMvc.get("/employers/{employerId}/reports/payruns/{payRunId}/net-totals", "emp-2", "run-9") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.employerId") { value("emp-2") }
            jsonPath("$.payRunId") { value("run-9") }
            jsonPath("$.countEmployees") { value(2) }
        }

        mockMvc.get("/employers/{employerId}/reports/paycheck-ledger/{paycheckId}", "emp-2", "chk-a") {
            param("includePayload", "true")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.employerId") { value("emp-2") }
            jsonPath("$.paycheckId") { value("chk-a") }
            jsonPath("$.entry.payRunId") { value("run-9") }
            jsonPath("$.entry.payload.paycheckId") { value("chk-a") }
        }

        mockMvc.get("/employers/{employerId}/reports/paycheck-ledger/{paycheckId}", "emp-2", "missing") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isNotFound() }
        }
    }
}

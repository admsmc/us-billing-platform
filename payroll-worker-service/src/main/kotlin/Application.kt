package com.example.uspayroll.worker

import com.example.uspayroll.payroll.engine.PayrollEngine
import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.shared.Money
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.PaycheckId
import com.example.uspayroll.shared.PayRunId
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.LocalDate

fun main() {
    embeddedServer(Netty, port = 8080) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        jackson { }
    }

    // Temporary config repositories; in future injected or provided via DI
    val earningConfig = InMemoryEarningConfigRepository()
    val deductionConfig = InMemoryDeductionConfigRepository()

    routing {
        post("/dry-run-paycheck") {
            // For now, ignore the body and use a hardcoded example input
            val employerId = EmployerId("emp-1")
            val employeeId = EmployeeId("ee-1")
            val period = PayPeriod(
                id = "2025-01-BW1",
                employerId = employerId,
                dateRange = LocalDateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 14)),
                checkDate = LocalDate.of(2025, 1, 15),
                frequency = PayFrequency.BIWEEKLY,
            )
            val snapshot = EmployeeSnapshot(
                employerId = employerId,
                employeeId = employeeId,
                homeState = "CA",
                workState = "CA",
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(260_000_00L),
                    frequency = period.frequency,
                ),
            )
            val input = PaycheckInput(
                paycheckId = PaycheckId("chk-dry-run"),
                payRunId = PayRunId("run-dry-run"),
                employerId = employerId,
                employeeId = employeeId,
                period = period,
                employeeSnapshot = snapshot,
                timeSlice = TimeSlice(
                    period = period,
                    regularHours = 0.0,
                    overtimeHours = 0.0,
                ),
                taxContext = TaxContext(),
                priorYtd = YtdSnapshot(year = 2025),
            )

            val result = PayrollEngine.calculatePaycheck(
                input = input,
                earningConfig = earningConfig,
                deductionConfig = deductionConfig,
            )

            call.respond(HttpStatusCode.OK, mapOf(
                "version" to PayrollEngine.version(),
                "paycheckId" to result.paycheckId.value,
                "employeeId" to result.employeeId.value,
                "grossCents" to result.gross.amount,
                "netCents" to result.net.amount,
            ))
        }
    }
}

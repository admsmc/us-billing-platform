package com.example.uspayroll.orchestrator.http

import com.example.uspayroll.orchestrator.persistence.PaycheckStoreRepository
import com.example.uspayroll.payroll.model.PaycheckResult
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.PayRunId
import com.example.uspayroll.shared.PaycheckId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/employers/{employerId}/paychecks")
class PaycheckController(
    private val paycheckStoreRepository: PaycheckStoreRepository,
) {

    data class PaycheckDto(
        val paycheckId: String,
        val payRunId: String?,
        val employerId: String,
        val employeeId: String,
        val payPeriodId: String,
        val checkDate: java.time.LocalDate,
        val frequency: String,
        val grossCents: Long,
        val netCents: Long,
    ) {
        companion object {
            fun fromDomain(p: PaycheckResult): PaycheckDto = PaycheckDto(
                paycheckId = p.paycheckId.value,
                payRunId = p.payRunId?.value,
                employerId = p.employerId.value,
                employeeId = p.employeeId.value,
                payPeriodId = p.period.id,
                checkDate = p.period.checkDate,
                frequency = p.period.frequency.name,
                grossCents = p.gross.amount,
                netCents = p.net.amount,
            )
        }
    }

    @GetMapping("/{paycheckId}")
    fun getPaycheck(
        @PathVariable employerId: String,
        @PathVariable paycheckId: String,
    ): ResponseEntity<PaycheckDto> {
        val paycheck = paycheckStoreRepository.findPaycheck(
            employerId = EmployerId(employerId),
            paycheckId = paycheckId,
        ) ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(PaycheckDto.fromDomain(paycheck))
    }
}

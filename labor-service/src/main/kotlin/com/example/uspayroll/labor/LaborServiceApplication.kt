package com.example.uspayroll.labor

import com.example.uspayroll.labor.api.LaborStandardsContextProvider
import com.example.uspayroll.labor.http.LaborStandardsContextDto
import com.example.uspayroll.labor.http.toDto
import com.example.uspayroll.shared.EmployerId
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@SpringBootApplication
class LaborServiceApplication

fun main(args: Array<String>) {
    runApplication<LaborServiceApplication>(*args)
}

@RestController
@RequestMapping("/employers/{employerId}")
class LaborHttpController(
    private val laborStandardsContextProvider: LaborStandardsContextProvider,
) {

    @GetMapping("/labor-standards")
    fun getLaborStandards(
        @PathVariable employerId: String,
        @RequestParam("asOf") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate,
        @RequestParam("state") workState: String,
        @RequestParam("homeState", required = false) homeState: String?,
        @RequestParam("locality", required = false) localityCodes: List<String>?,
    ): LaborStandardsContextDto {
        val context = laborStandardsContextProvider.getLaborStandards(
            employerId = EmployerId(employerId),
            asOfDate = asOf,
            workState = workState,
            homeState = homeState,
            localityCodes = localityCodes ?: emptyList(),
        )

        // Returning null here produces a 200 with an empty body, which causes some RestTemplate clients
        // (especially Kotlin-typed ones) to throw at the call site. Provide a safe baseline instead.
        return context?.toDto() ?: LaborStandardsContextDto(
            federalMinimumWageCents = 725, // $7.25
        )
    }
}

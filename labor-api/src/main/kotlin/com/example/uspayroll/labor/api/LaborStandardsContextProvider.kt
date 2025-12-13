package com.example.uspayroll.labor.api

import com.example.uspayroll.payroll.model.LaborStandardsContext
import com.example.uspayroll.shared.EmployerId
import java.time.LocalDate

/** Provides engine-facing LaborStandardsContext for a given employer/date/location. */
interface LaborStandardsContextProvider {
    fun getLaborStandards(employerId: EmployerId, asOfDate: LocalDate, workState: String?, homeState: String?, localityCodes: List<String> = emptyList()): LaborStandardsContext?
}

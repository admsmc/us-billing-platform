package com.example.usbilling.labor.api

import com.example.usbilling.payroll.model.LaborStandardsContext
import com.example.usbilling.shared.EmployerId
import java.time.LocalDate

/** Provides engine-facing LaborStandardsContext for a given employer/date/location. */
interface LaborStandardsContextProvider {
    fun getLaborStandards(employerId: EmployerId, asOfDate: LocalDate, workState: String?, homeState: String?, localityCodes: List<String> = emptyList()): LaborStandardsContext?
}

package com.example.usbilling.csrportal.controller

import com.example.usbilling.csrportal.service.Customer360Data
import com.example.usbilling.csrportal.service.Customer360Service
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/csr/customers/{customerId}")
class Customer360Controller(
    private val customer360Service: Customer360Service,
) {

    /**
     * Get comprehensive 360° view of a customer.
     * Aggregates data from all services.
     */
    @GetMapping("/360")
    fun getCustomer360(
        @PathVariable customerId: String,
        @RequestParam utilityId: String,
    ): ResponseEntity<Customer360Data> {
        val data = customer360Service.getCustomer360(customerId, utilityId)
        return ResponseEntity.ok(data)
    }
}

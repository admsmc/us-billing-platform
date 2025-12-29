package com.example.usbilling.portal.controller

import com.example.usbilling.portal.security.CustomerPrincipal
import com.example.usbilling.portal.service.AppointmentAvailabilityService
import com.example.usbilling.portal.service.AppointmentBookingResult
import com.example.usbilling.portal.service.AppointmentSlot
import com.example.usbilling.portal.service.CustomerAppointment
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@RestController
@RequestMapping("/api/customers/me/appointments")
class AppointmentController(
    private val appointmentService: AppointmentAvailabilityService,
) {

    /**
     * Get available appointment slots.
     */
    @GetMapping("/availability")
    fun getAvailability(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestParam serviceType: String,
        @RequestParam startDate: LocalDate,
        @RequestParam(required = false) endDate: LocalDate?,
    ): ResponseEntity<AvailabilityResponse> {
        val end = endDate ?: startDate.plusDays(14) // Default to 2 weeks

        val slots = appointmentService.getAvailableSlots(
            utilityId = principal.utilityId,
            serviceType = serviceType,
            startDate = startDate,
            endDate = end,
        )

        return ResponseEntity.ok(
            AvailabilityResponse(
                slots = slots,
                startDate = startDate,
                endDate = end,
            ),
        )
    }

    /**
     * Book an appointment for a service request.
     */
    @PostMapping
    fun bookAppointment(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestBody request: BookAppointmentRequest,
    ): ResponseEntity<AppointmentBookingResult> {
        val result = appointmentService.bookAppointment(
            utilityId = principal.utilityId,
            customerId = principal.customerId,
            requestId = request.requestId,
            scheduledDate = request.scheduledDate,
            timeWindow = request.timeWindow,
        )

        return ResponseEntity.ok(result)
    }

    /**
     * List customer's appointments.
     */
    @GetMapping
    fun listAppointments(
        @AuthenticationPrincipal principal: CustomerPrincipal,
    ): ResponseEntity<AppointmentListResponse> {
        val appointments = appointmentService.listAppointments(
            utilityId = principal.utilityId,
            customerId = principal.customerId,
        )

        return ResponseEntity.ok(
            AppointmentListResponse(
                appointments = appointments,
            ),
        )
    }

    /**
     * Reschedule an appointment.
     */
    @PutMapping("/{requestId}/reschedule")
    fun rescheduleAppointment(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable requestId: String,
        @RequestBody request: RescheduleRequest,
    ): ResponseEntity<AppointmentBookingResult> {
        val result = appointmentService.rescheduleAppointment(
            utilityId = principal.utilityId,
            requestId = requestId,
            newDate = request.scheduledDate,
            newTimeWindow = request.timeWindow,
        )

        return ResponseEntity.ok(result)
    }

    /**
     * Cancel an appointment.
     */
    @DeleteMapping("/{requestId}")
    fun cancelAppointment(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable requestId: String,
    ): ResponseEntity<AppointmentBookingResult> {
        val result = appointmentService.cancelAppointment(
            utilityId = principal.utilityId,
            requestId = requestId,
        )

        return ResponseEntity.ok(result)
    }
}

// Request DTOs

data class BookAppointmentRequest(
    val requestId: String,
    val scheduledDate: LocalDate,
    val timeWindow: String,
)

data class RescheduleRequest(
    val scheduledDate: LocalDate,
    val timeWindow: String,
)

// Response DTOs

data class AvailabilityResponse(
    val slots: List<AppointmentSlot>,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

data class AppointmentListResponse(
    val appointments: List<CustomerAppointment>,
)

package com.example.usbilling.hr.controller

import com.example.usbilling.hr.domain.*
import com.example.usbilling.hr.service.ServiceRequestService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@RestController
@RequestMapping("/utilities/{utilityId}/service-requests")
class ServiceRequestController(
    private val serviceRequestService: ServiceRequestService,
) {

    /**
     * Submit a new service request.
     */
    @PostMapping
    fun submitServiceRequest(
        @PathVariable utilityId: String,
        @RequestBody request: ServiceRequestSubmissionRequest,
    ): ResponseEntity<ServiceRequestResponse> {
        val serviceRequest = serviceRequestService.submitRequest(
            utilityId = utilityId,
            customerId = request.customerId,
            accountId = request.accountId,
            requestType = ServiceRequestType.valueOf(request.requestType),
            serviceType = request.serviceType,
            serviceAddress = request.serviceAddress,
            requestedDate = request.requestedDate,
            notes = request.notes,
        )

        return ResponseEntity.ok(serviceRequest.toResponse())
    }

    /**
     * Get a service request by ID.
     */
    @GetMapping("/{requestId}")
    fun getServiceRequest(
        @PathVariable utilityId: String,
        @PathVariable requestId: String,
    ): ResponseEntity<ServiceRequestDetailResponse> {
        val request = serviceRequestService.findRequestById(requestId)
            ?: return ResponseEntity.notFound().build()

        val appointment = serviceRequestService.findAppointmentByRequestId(requestId)

        return ResponseEntity.ok(
            ServiceRequestDetailResponse(
                request = request.toResponse(),
                appointment = appointment?.toResponse(),
            ),
        )
    }

    /**
     * Get service requests for a customer.
     */
    @GetMapping("/customers/{customerId}")
    fun getCustomerServiceRequests(
        @PathVariable utilityId: String,
        @PathVariable customerId: String,
        @RequestParam(defaultValue = "50") limit: Int,
    ): ResponseEntity<ServiceRequestListResponse> {
        val requests = serviceRequestService.findRequestsByCustomer(customerId, limit)
        return ResponseEntity.ok(
            ServiceRequestListResponse(
                requests = requests.map { it.toResponse() },
            ),
        )
    }

    /**
     * Schedule an appointment for a service request.
     */
    @PostMapping("/{requestId}/schedule")
    fun scheduleAppointment(
        @PathVariable utilityId: String,
        @PathVariable requestId: String,
        @RequestBody request: ScheduleAppointmentRequest,
    ): ResponseEntity<AppointmentResponse> {
        val appointment = serviceRequestService.scheduleAppointment(
            requestId = requestId,
            scheduledDate = request.scheduledDate,
            timeWindow = AppointmentTimeWindow.valueOf(request.timeWindow),
            startTime = request.startTime,
            endTime = request.endTime,
        )

        return ResponseEntity.ok(appointment.toResponse())
    }

    /**
     * Reschedule an appointment.
     */
    @PostMapping("/{requestId}/reschedule")
    fun rescheduleAppointment(
        @PathVariable utilityId: String,
        @PathVariable requestId: String,
        @RequestBody request: RescheduleAppointmentRequest,
    ): ResponseEntity<AppointmentResponse> {
        val appointment = serviceRequestService.rescheduleAppointment(
            requestId = requestId,
            newScheduledDate = request.scheduledDate,
            newTimeWindow = AppointmentTimeWindow.valueOf(request.timeWindow),
            startTime = request.startTime,
            endTime = request.endTime,
        )

        return ResponseEntity.ok(appointment.toResponse())
    }

    /**
     * Cancel a service request.
     */
    @PutMapping("/{requestId}/cancel")
    fun cancelServiceRequest(
        @PathVariable utilityId: String,
        @PathVariable requestId: String,
    ): ResponseEntity<ServiceRequestResponse> {
        val request = serviceRequestService.cancelRequest(requestId)
        return ResponseEntity.ok(request.toResponse())
    }

    /**
     * Update service request status (internal use).
     */
    @PutMapping("/{requestId}/status")
    fun updateStatus(
        @PathVariable utilityId: String,
        @PathVariable requestId: String,
        @RequestBody request: UpdateStatusRequest,
    ): ResponseEntity<ServiceRequestResponse> {
        val updated = serviceRequestService.updateStatus(
            requestId = requestId,
            newStatus = ServiceRequestStatus.valueOf(request.status),
        )
        return ResponseEntity.ok(updated.toResponse())
    }

    /**
     * Assign a work order to a service request (internal use).
     */
    @PutMapping("/{requestId}/work-order")
    fun assignWorkOrder(
        @PathVariable utilityId: String,
        @PathVariable requestId: String,
        @RequestBody request: AssignWorkOrderRequest,
    ): ResponseEntity<ServiceRequestResponse> {
        val updated = serviceRequestService.assignWorkOrder(
            requestId = requestId,
            workOrderId = request.workOrderId,
        )
        return ResponseEntity.ok(updated.toResponse())
    }

    /**
     * Link a service request to a case (internal use).
     */
    @PutMapping("/{requestId}/case")
    fun linkToCase(
        @PathVariable utilityId: String,
        @PathVariable requestId: String,
        @RequestBody request: LinkToCaseRequest,
    ): ResponseEntity<ServiceRequestResponse> {
        val updated = serviceRequestService.linkToCase(
            requestId = requestId,
            caseId = request.caseId,
        )
        return ResponseEntity.ok(updated.toResponse())
    }
}

// Request DTOs

data class ServiceRequestSubmissionRequest(
    val customerId: String,
    val accountId: String,
    val requestType: String,
    val serviceType: String,
    val serviceAddress: String,
    val requestedDate: LocalDate?,
    val notes: String?,
)

data class ScheduleAppointmentRequest(
    val scheduledDate: LocalDate,
    val timeWindow: String,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
)

data class RescheduleAppointmentRequest(
    val scheduledDate: LocalDate,
    val timeWindow: String,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
)

data class UpdateStatusRequest(
    val status: String,
)

data class AssignWorkOrderRequest(
    val workOrderId: String,
)

data class LinkToCaseRequest(
    val caseId: String,
)

// Response DTOs

data class ServiceRequestResponse(
    val requestId: String,
    val utilityId: String,
    val customerId: String,
    val accountId: String,
    val requestType: ServiceRequestType,
    val serviceType: String,
    val serviceAddress: String,
    val requestedDate: LocalDate?,
    val status: ServiceRequestStatus,
    val priority: ServiceRequestPriority,
    val workOrderId: String?,
    val notes: String?,
    val submittedAt: LocalDateTime,
    val completedAt: LocalDateTime?,
    val caseId: String?,
)

data class AppointmentResponse(
    val appointmentId: String,
    val requestId: String,
    val scheduledDate: LocalDate,
    val timeWindow: AppointmentTimeWindow,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
    val technicianId: String?,
    val status: AppointmentStatus,
    val customerNotified: Boolean,
    val notes: String?,
    val createdAt: LocalDateTime,
)

data class ServiceRequestDetailResponse(
    val request: ServiceRequestResponse,
    val appointment: AppointmentResponse?,
)

data class ServiceRequestListResponse(
    val requests: List<ServiceRequestResponse>,
)

// Extension functions for mapping

fun ServiceRequest.toResponse() = ServiceRequestResponse(
    requestId = requestId,
    utilityId = utilityId,
    customerId = customerId,
    accountId = accountId,
    requestType = requestType,
    serviceType = serviceType,
    serviceAddress = serviceAddress,
    requestedDate = requestedDate,
    status = status,
    priority = priority,
    workOrderId = workOrderId,
    notes = notes,
    submittedAt = submittedAt,
    completedAt = completedAt,
    caseId = caseId,
)

fun ServiceRequestAppointment.toResponse() = AppointmentResponse(
    appointmentId = appointmentId,
    requestId = requestId,
    scheduledDate = scheduledDate,
    timeWindow = timeWindow,
    startTime = startTime,
    endTime = endTime,
    technicianId = technicianId,
    status = status,
    customerNotified = customerNotified,
    notes = notes,
    createdAt = createdAt,
)

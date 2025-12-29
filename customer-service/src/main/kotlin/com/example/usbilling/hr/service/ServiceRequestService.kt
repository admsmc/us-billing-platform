package com.example.usbilling.hr.service

import com.example.usbilling.hr.domain.*
import com.example.usbilling.hr.repository.ServiceRequestAppointmentRepository
import com.example.usbilling.hr.repository.ServiceRequestRepository
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.*

@Service
class ServiceRequestService(
    private val requestRepository: ServiceRequestRepository,
    private val appointmentRepository: ServiceRequestAppointmentRepository,
) {

    fun submitRequest(
        utilityId: String,
        customerId: String,
        accountId: String,
        requestType: ServiceRequestType,
        serviceType: String,
        serviceAddress: String,
        requestedDate: LocalDate?,
        notes: String?,
    ): ServiceRequest {
        val priority = determinePriority(requestType)
        val request = ServiceRequest(
            requestId = UUID.randomUUID().toString(),
            utilityId = utilityId,
            customerId = customerId,
            accountId = accountId,
            requestType = requestType,
            serviceType = serviceType,
            serviceAddress = serviceAddress,
            requestedDate = requestedDate,
            status = ServiceRequestStatus.SUBMITTED,
            priority = priority,
            workOrderId = null,
            notes = notes,
            submittedAt = LocalDateTime.now(),
            completedAt = null,
            caseId = null,
        )
        return requestRepository.save(request)
    }

    fun scheduleAppointment(
        requestId: String,
        scheduledDate: LocalDate,
        timeWindow: AppointmentTimeWindow,
        startTime: LocalTime? = null,
        endTime: LocalTime? = null,
    ): ServiceRequestAppointment {
        val request = requestRepository.findById(requestId)
            ?: throw IllegalArgumentException("Service request $requestId not found")

        if (request.status != ServiceRequestStatus.SUBMITTED) {
            throw IllegalStateException("Cannot schedule appointment for request in status ${request.status}")
        }

        val appointment = ServiceRequestAppointment(
            appointmentId = UUID.randomUUID().toString(),
            requestId = requestId,
            scheduledDate = scheduledDate,
            timeWindow = timeWindow,
            startTime = startTime,
            endTime = endTime,
            technicianId = null,
            status = AppointmentStatus.SCHEDULED,
            customerNotified = false,
            notes = null,
            createdAt = LocalDateTime.now(),
        )

        appointmentRepository.save(appointment)

        // Update request status to SCHEDULED
        val updated = request.copy(status = ServiceRequestStatus.SCHEDULED)
        requestRepository.save(updated)

        return appointment
    }

    fun rescheduleAppointment(
        requestId: String,
        newScheduledDate: LocalDate,
        newTimeWindow: AppointmentTimeWindow,
        startTime: LocalTime? = null,
        endTime: LocalTime? = null,
    ): ServiceRequestAppointment {
        val existing = appointmentRepository.findByRequestId(requestId)
            ?: throw IllegalArgumentException("No appointment found for request $requestId")

        if (existing.status == AppointmentStatus.COMPLETED || existing.status == AppointmentStatus.CANCELLED) {
            throw IllegalStateException("Cannot reschedule appointment in status ${existing.status}")
        }

        // Mark old appointment as RESCHEDULED
        val oldUpdated = existing.copy(status = AppointmentStatus.RESCHEDULED)
        appointmentRepository.save(oldUpdated)

        // Create new appointment
        val newAppointment = ServiceRequestAppointment(
            appointmentId = UUID.randomUUID().toString(),
            requestId = requestId,
            scheduledDate = newScheduledDate,
            timeWindow = newTimeWindow,
            startTime = startTime,
            endTime = endTime,
            technicianId = existing.technicianId,
            status = AppointmentStatus.SCHEDULED,
            customerNotified = false,
            notes = "Rescheduled from ${existing.scheduledDate}",
            createdAt = LocalDateTime.now(),
        )

        return appointmentRepository.save(newAppointment)
    }

    fun cancelRequest(requestId: String): ServiceRequest {
        val request = requestRepository.findById(requestId)
            ?: throw IllegalArgumentException("Service request $requestId not found")

        if (request.status == ServiceRequestStatus.COMPLETED || request.status == ServiceRequestStatus.CANCELLED) {
            throw IllegalStateException("Cannot cancel request in status ${request.status}")
        }

        val updated = request.copy(status = ServiceRequestStatus.CANCELLED)
        requestRepository.save(updated)

        // Cancel any associated appointment
        appointmentRepository.findByRequestId(requestId)?.let { appointment ->
            if (appointment.status != AppointmentStatus.COMPLETED) {
                val cancelledAppointment = appointment.copy(status = AppointmentStatus.CANCELLED)
                appointmentRepository.save(cancelledAppointment)
            }
        }

        return updated
    }

    fun updateStatus(requestId: String, newStatus: ServiceRequestStatus): ServiceRequest {
        val request = requestRepository.findById(requestId)
            ?: throw IllegalArgumentException("Service request $requestId not found")

        val updated = request.copy(
            status = newStatus,
            completedAt = if (newStatus == ServiceRequestStatus.COMPLETED) LocalDateTime.now() else request.completedAt,
        )
        return requestRepository.save(updated)
    }

    fun assignWorkOrder(requestId: String, workOrderId: String): ServiceRequest {
        val request = requestRepository.findById(requestId)
            ?: throw IllegalArgumentException("Service request $requestId not found")

        val updated = request.copy(workOrderId = workOrderId)
        return requestRepository.save(updated)
    }

    fun linkToCase(requestId: String, caseId: String): ServiceRequest {
        val request = requestRepository.findById(requestId)
            ?: throw IllegalArgumentException("Service request $requestId not found")

        val updated = request.copy(caseId = caseId)
        return requestRepository.save(updated)
    }

    fun findRequestById(requestId: String): ServiceRequest? = requestRepository.findById(requestId)

    fun findRequestsByCustomer(customerId: String, limit: Int = 50): List<ServiceRequest> = requestRepository.findByCustomerId(customerId, limit)

    fun findAppointmentByRequestId(requestId: String): ServiceRequestAppointment? = appointmentRepository.findByRequestId(requestId)

    private fun determinePriority(requestType: ServiceRequestType): ServiceRequestPriority = when (requestType) {
        ServiceRequestType.RECONNECT -> ServiceRequestPriority.URGENT
        ServiceRequestType.START_SERVICE,
        ServiceRequestType.STOP_SERVICE,
        ServiceRequestType.TRANSFER_SERVICE,
        ServiceRequestType.MOVE_SERVICE,
        ServiceRequestType.METER_TEST,
        -> ServiceRequestPriority.NORMAL
    }
}

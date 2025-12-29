package com.example.usbilling.hr.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Service request for utility service changes.
 */
data class ServiceRequest(
    val requestId: String,
    val utilityId: String,
    val customerId: String,
    val accountId: String,
    val requestType: ServiceRequestType,
    val serviceType: String, // ELECTRIC, GAS, WATER
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

/**
 * Service request appointment.
 */
data class ServiceRequestAppointment(
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

enum class ServiceRequestType {
    START_SERVICE,
    STOP_SERVICE,
    TRANSFER_SERVICE,
    MOVE_SERVICE,
    METER_TEST,
    RECONNECT,
}

enum class ServiceRequestStatus {
    SUBMITTED,
    SCHEDULED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
}

enum class ServiceRequestPriority {
    NORMAL,
    URGENT,
    EMERGENCY,
}

enum class AppointmentTimeWindow {
    AM,
    PM,
    MORNING,
    AFTERNOON,
    SPECIFIC_TIME,
}

enum class AppointmentStatus {
    SCHEDULED,
    CONFIRMED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    RESCHEDULED,
}

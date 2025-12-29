package com.example.usbilling.portal.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.time.LocalDate

@Service
class AppointmentAvailabilityService(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${customer-portal.customer-service-url}")
    private val customerServiceUrl: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val customerClient: WebClient by lazy {
        webClientBuilder.baseUrl(customerServiceUrl).build()
    }

    /**
     * Get available appointment slots for a given date range and service type.
     */
    fun getAvailableSlots(
        utilityId: String,
        serviceType: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<AppointmentSlot> {
        logger.info("Fetching available slots for $serviceType from $startDate to $endDate")

        return try {
            val response = customerClient
                .get()
                .uri(
                    "/utilities/{utilityId}/appointments/availability?serviceType={serviceType}&startDate={startDate}&endDate={endDate}",
                    utilityId,
                    serviceType,
                    startDate,
                    endDate,
                )
                .retrieve()
                .bodyToMono(Map::class.java)
                .block()

            @Suppress("UNCHECKED_CAST")
            val slots = response?.get("slots") as? List<Map<String, Any>> ?: emptyList()

            slots.map { slot ->
                AppointmentSlot(
                    date = LocalDate.parse(slot["date"] as String),
                    timeWindow = slot["timeWindow"] as String,
                    availableCount = (slot["availableCount"] as Number).toInt(),
                    technicianIds = (slot["technicianIds"] as? List<*>)?.map { it.toString() } ?: emptyList(),
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch available slots", e)
            emptyList()
        }
    }

    /**
     * Book an appointment.
     */
    fun bookAppointment(
        utilityId: String,
        customerId: String,
        requestId: String,
        scheduledDate: LocalDate,
        timeWindow: String,
    ): AppointmentBookingResult {
        logger.info("Booking appointment for request $requestId on $scheduledDate $timeWindow")

        return try {
            val response = customerClient
                .post()
                .uri("/utilities/{utilityId}/service-requests/{requestId}/schedule", utilityId, requestId)
                .bodyValue(
                    mapOf(
                        "scheduledDate" to scheduledDate.toString(),
                        "timeWindow" to timeWindow,
                    ),
                )
                .retrieve()
                .bodyToMono(Map::class.java)
                .block()

            AppointmentBookingResult.success(
                appointmentId = response?.get("appointmentId") as? String ?: "",
                message = "Appointment booked successfully",
            )
        } catch (e: Exception) {
            logger.error("Failed to book appointment", e)
            AppointmentBookingResult.failure("Failed to book appointment: ${e.message}")
        }
    }

    /**
     * List customer's appointments.
     */
    fun listAppointments(
        utilityId: String,
        customerId: String,
    ): List<CustomerAppointment> {
        logger.info("Listing appointments for customer $customerId")

        return try {
            val response = customerClient
                .get()
                .uri("/utilities/{utilityId}/customers/{customerId}/service-requests", utilityId, customerId)
                .retrieve()
                .bodyToMono(Map::class.java)
                .block()

            @Suppress("UNCHECKED_CAST")
            val requests = response?.get("requests") as? List<Map<String, Any>> ?: emptyList()

            requests.mapNotNull { request ->
                val appointment = request["appointment"] as? Map<String, Any>
                if (appointment != null) {
                    CustomerAppointment(
                        appointmentId = appointment["appointmentId"] as String,
                        requestId = request["requestId"] as String,
                        requestType = request["requestType"] as String,
                        serviceType = request["serviceType"] as String,
                        scheduledDate = LocalDate.parse(appointment["scheduledDate"] as String),
                        timeWindow = appointment["timeWindow"] as String,
                        status = appointment["status"] as String,
                        technicianName = appointment["technicianName"] as? String,
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to list appointments", e)
            emptyList()
        }
    }

    /**
     * Reschedule an appointment.
     */
    fun rescheduleAppointment(
        utilityId: String,
        requestId: String,
        newDate: LocalDate,
        newTimeWindow: String,
    ): AppointmentBookingResult {
        logger.info("Rescheduling appointment for request $requestId to $newDate $newTimeWindow")

        return try {
            val response = customerClient
                .post()
                .uri("/utilities/{utilityId}/service-requests/{requestId}/reschedule", utilityId, requestId)
                .bodyValue(
                    mapOf(
                        "scheduledDate" to newDate.toString(),
                        "timeWindow" to newTimeWindow,
                    ),
                )
                .retrieve()
                .bodyToMono(Map::class.java)
                .block()

            AppointmentBookingResult.success(
                appointmentId = response?.get("appointmentId") as? String ?: "",
                message = "Appointment rescheduled successfully",
            )
        } catch (e: Exception) {
            logger.error("Failed to reschedule appointment", e)
            AppointmentBookingResult.failure("Failed to reschedule: ${e.message}")
        }
    }

    /**
     * Cancel an appointment.
     */
    fun cancelAppointment(
        utilityId: String,
        requestId: String,
    ): AppointmentBookingResult {
        logger.info("Canceling appointment for request $requestId")

        return try {
            customerClient
                .put()
                .uri("/utilities/{utilityId}/service-requests/{requestId}/cancel", utilityId, requestId)
                .retrieve()
                .bodyToMono(Map::class.java)
                .block()

            AppointmentBookingResult.success(
                appointmentId = null,
                message = "Appointment cancelled successfully",
            )
        } catch (e: Exception) {
            logger.error("Failed to cancel appointment", e)
            AppointmentBookingResult.failure("Failed to cancel: ${e.message}")
        }
    }
}

data class AppointmentSlot(
    val date: LocalDate,
    val timeWindow: String,
    val availableCount: Int,
    val technicianIds: List<String>,
)

data class CustomerAppointment(
    val appointmentId: String,
    val requestId: String,
    val requestType: String,
    val serviceType: String,
    val scheduledDate: LocalDate,
    val timeWindow: String,
    val status: String,
    val technicianName: String?,
)

sealed class AppointmentBookingResult {
    abstract val success: Boolean
    abstract val message: String

    data class Success(
        val appointmentId: String?,
        override val message: String,
    ) : AppointmentBookingResult() {
        override val success: Boolean = true
    }

    data class Failure(
        override val message: String,
    ) : AppointmentBookingResult() {
        override val success: Boolean = false
    }

    companion object {
        fun success(appointmentId: String?, message: String) = Success(appointmentId, message)
        fun failure(message: String) = Failure(message)
    }
}

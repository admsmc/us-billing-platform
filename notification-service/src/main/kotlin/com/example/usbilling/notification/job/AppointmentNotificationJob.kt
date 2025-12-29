package com.example.usbilling.notification.job

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Scheduled job for sending appointment notifications:
 * - Confirmation (1 day before)
 * - Reminder (2 hours before)
 * - En-route notification (when technician is on the way)
 * - Completion confirmation (after service)
 */
@Component
class AppointmentNotificationJob(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${notification.customer-service-url}")
    private val customerServiceUrl: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val customerClient: WebClient by lazy {
        webClientBuilder.baseUrl(customerServiceUrl).build()
    }

    /**
     * Send appointment confirmations (1 day before).
     * Runs daily at 9 AM.
     */
    @Scheduled(cron = "0 0 9 * * *")
    fun sendAppointmentConfirmations() {
        logger.info("Checking for appointments needing confirmation (1 day before)")

        val tomorrow = LocalDate.now().plusDays(1)

        try {
            val appointments = fetchAppointments(tomorrow)

            appointments.forEach { appointment ->
                sendConfirmation(appointment)
            }

            logger.info("Sent ${appointments.size} appointment confirmations")
        } catch (e: Exception) {
            logger.error("Failed to send appointment confirmations", e)
        }
    }

    /**
     * Send appointment reminders (2 hours before).
     * Runs every hour.
     */
    @Scheduled(cron = "0 0 * * * *")
    fun sendAppointmentReminders() {
        logger.info("Checking for appointments needing reminders (2 hours before)")

        val now = LocalDateTime.now()
        val twoHoursFromNow = now.plusHours(2)

        try {
            val appointments = fetchUpcomingAppointments(twoHoursFromNow)

            appointments.forEach { appointment ->
                sendReminder(appointment)
            }

            logger.info("Sent ${appointments.size} appointment reminders")
        } catch (e: Exception) {
            logger.error("Failed to send appointment reminders", e)
        }
    }

    private fun fetchAppointments(date: LocalDate): List<AppointmentInfo> = try {
        val response = customerClient
            .get()
            .uri("/appointments/date/{date}", date)
            .retrieve()
            .bodyToMono(Map::class.java)
            .block()

        @Suppress("UNCHECKED_CAST")
        val appointments = response?.get("appointments") as? List<Map<String, Any>> ?: emptyList()

        appointments.map { mapToAppointmentInfo(it) }
    } catch (e: Exception) {
        logger.error("Failed to fetch appointments for date: $date", e)
        emptyList()
    }

    private fun fetchUpcomingAppointments(dateTime: LocalDateTime): List<AppointmentInfo> = try {
        val response = customerClient
            .get()
            .uri("/appointments/upcoming?dateTime={dateTime}", dateTime)
            .retrieve()
            .bodyToMono(Map::class.java)
            .block()

        @Suppress("UNCHECKED_CAST")
        val appointments = response?.get("appointments") as? List<Map<String, Any>> ?: emptyList()

        appointments.map { mapToAppointmentInfo(it) }
    } catch (e: Exception) {
        logger.error("Failed to fetch upcoming appointments", e)
        emptyList()
    }

    private fun mapToAppointmentInfo(data: Map<String, Any>): AppointmentInfo = AppointmentInfo(
        appointmentId = data["appointmentId"] as String,
        customerId = data["customerId"] as String,
        customerEmail = data["customerEmail"] as? String,
        customerPhone = data["customerPhone"] as? String,
        serviceType = data["serviceType"] as String,
        scheduledDate = LocalDate.parse(data["scheduledDate"] as String),
        timeWindow = data["timeWindow"] as String,
        technicianName = data["technicianName"] as? String,
    )

    private fun sendConfirmation(appointment: AppointmentInfo) {
        logger.info("Sending confirmation for appointment: ${appointment.appointmentId}")

        // Send email confirmation
        appointment.customerEmail?.let { email ->
            sendEmail(
                to = email,
                subject = "Appointment Confirmation - ${appointment.scheduledDate}",
                body = buildConfirmationMessage(appointment),
            )
        }

        // Send SMS confirmation
        appointment.customerPhone?.let { phone ->
            sendSms(
                to = phone,
                body = "Your appointment is confirmed for ${appointment.scheduledDate} ${appointment.timeWindow}. " +
                    "Service: ${appointment.serviceType}",
            )
        }
    }

    private fun sendReminder(appointment: AppointmentInfo) {
        logger.info("Sending reminder for appointment: ${appointment.appointmentId}")

        // Send SMS reminder (more immediate for 2-hour window)
        appointment.customerPhone?.let { phone ->
            sendSms(
                to = phone,
                body = "Reminder: Your ${appointment.serviceType} appointment is in 2 hours. " +
                    "${appointment.technicianName ?: "Our technician"} will arrive during ${appointment.timeWindow}.",
            )
        }
    }

    private fun buildConfirmationMessage(appointment: AppointmentInfo): String = """
            Your appointment has been confirmed.
            
            Date: ${appointment.scheduledDate}
            Time Window: ${appointment.timeWindow}
            Service: ${appointment.serviceType}
            ${if (appointment.technicianName != null) "Technician: ${appointment.technicianName}" else ""}
            
            You will receive a reminder 2 hours before your appointment.
            
            If you need to reschedule or cancel, please log in to your account.
    """.trimIndent()

    private fun sendEmail(to: String, subject: String, body: String) {
        // TODO: Integrate with email provider
        logger.info("Would send email to $to: $subject")
    }

    private fun sendSms(to: String, body: String) {
        // TODO: Integrate with SMS provider
        logger.info("Would send SMS to $to: $body")
    }
}

data class AppointmentInfo(
    val appointmentId: String,
    val customerId: String,
    val customerEmail: String?,
    val customerPhone: String?,
    val serviceType: String,
    val scheduledDate: LocalDate,
    val timeWindow: String,
    val technicianName: String?,
)

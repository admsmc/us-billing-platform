package com.example.usbilling.hr.repository

import com.example.usbilling.hr.domain.*
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class ServiceRequestRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {

    fun save(request: ServiceRequest): ServiceRequest {
        val sql = """
            INSERT INTO service_request (
                request_id, utility_id, customer_id, account_id, request_type,
                service_type, service_address, requested_date, status, priority,
                work_order_id, notes, submitted_at, completed_at, case_id
            ) VALUES (
                :requestId, :utilityId, :customerId, :accountId, :requestType,
                :serviceType, :serviceAddress, :requestedDate, :status, :priority,
                :workOrderId, :notes, :submittedAt, :completedAt, :caseId
            )
            ON CONFLICT (request_id) DO UPDATE SET
                status = EXCLUDED.status,
                work_order_id = EXCLUDED.work_order_id,
                completed_at = EXCLUDED.completed_at,
                case_id = EXCLUDED.case_id,
                notes = EXCLUDED.notes
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("requestId", request.requestId)
            .addValue("utilityId", request.utilityId)
            .addValue("customerId", request.customerId)
            .addValue("accountId", request.accountId)
            .addValue("requestType", request.requestType.name)
            .addValue("serviceType", request.serviceType)
            .addValue("serviceAddress", request.serviceAddress)
            .addValue("requestedDate", request.requestedDate)
            .addValue("status", request.status.name)
            .addValue("priority", request.priority.name)
            .addValue("workOrderId", request.workOrderId)
            .addValue("notes", request.notes)
            .addValue("submittedAt", request.submittedAt)
            .addValue("completedAt", request.completedAt)
            .addValue("caseId", request.caseId)

        jdbcTemplate.update(sql, params)
        return request
    }

    fun findById(requestId: String): ServiceRequest? {
        val sql = "SELECT * FROM service_request WHERE request_id = :requestId"
        val params = MapSqlParameterSource("requestId", requestId)
        return jdbcTemplate.query(sql, params) { rs, _ -> mapRequest(rs) }.firstOrNull()
    }

    fun findByCustomerId(customerId: String, limit: Int = 50): List<ServiceRequest> {
        val sql = """
            SELECT * FROM service_request 
            WHERE customer_id = :customerId 
            ORDER BY submitted_at DESC 
            LIMIT :limit
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("customerId", customerId)
            .addValue("limit", limit)

        return jdbcTemplate.query(sql, params) { rs, _ -> mapRequest(rs) }
    }

    fun findByStatus(status: ServiceRequestStatus, limit: Int = 100): List<ServiceRequest> {
        val sql = """
            SELECT * FROM service_request 
            WHERE status = :status 
            ORDER BY submitted_at ASC 
            LIMIT :limit
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("status", status.name)
            .addValue("limit", limit)

        return jdbcTemplate.query(sql, params) { rs, _ -> mapRequest(rs) }
    }

    private fun mapRequest(rs: ResultSet) = ServiceRequest(
        requestId = rs.getString("request_id"),
        utilityId = rs.getString("utility_id"),
        customerId = rs.getString("customer_id"),
        accountId = rs.getString("account_id"),
        requestType = ServiceRequestType.valueOf(rs.getString("request_type")),
        serviceType = rs.getString("service_type"),
        serviceAddress = rs.getString("service_address"),
        requestedDate = rs.getDate("requested_date")?.toLocalDate(),
        status = ServiceRequestStatus.valueOf(rs.getString("status")),
        priority = ServiceRequestPriority.valueOf(rs.getString("priority")),
        workOrderId = rs.getString("work_order_id"),
        notes = rs.getString("notes"),
        submittedAt = rs.getTimestamp("submitted_at").toLocalDateTime(),
        completedAt = rs.getTimestamp("completed_at")?.toLocalDateTime(),
        caseId = rs.getString("case_id"),
    )
}

@Repository
class ServiceRequestAppointmentRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {

    fun save(appointment: ServiceRequestAppointment): ServiceRequestAppointment {
        val sql = """
            INSERT INTO service_request_appointment (
                appointment_id, request_id, scheduled_date, time_window,
                start_time, end_time, technician_id, status,
                customer_notified, notes, created_at
            ) VALUES (
                :appointmentId, :requestId, :scheduledDate, :timeWindow,
                :startTime, :endTime, :technicianId, :status,
                :customerNotified, :notes, :createdAt
            )
            ON CONFLICT (appointment_id) DO UPDATE SET
                scheduled_date = EXCLUDED.scheduled_date,
                time_window = EXCLUDED.time_window,
                start_time = EXCLUDED.start_time,
                end_time = EXCLUDED.end_time,
                technician_id = EXCLUDED.technician_id,
                status = EXCLUDED.status,
                customer_notified = EXCLUDED.customer_notified,
                notes = EXCLUDED.notes
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("appointmentId", appointment.appointmentId)
            .addValue("requestId", appointment.requestId)
            .addValue("scheduledDate", appointment.scheduledDate)
            .addValue("timeWindow", appointment.timeWindow.name)
            .addValue("startTime", appointment.startTime)
            .addValue("endTime", appointment.endTime)
            .addValue("technicianId", appointment.technicianId)
            .addValue("status", appointment.status.name)
            .addValue("customerNotified", appointment.customerNotified)
            .addValue("notes", appointment.notes)
            .addValue("createdAt", appointment.createdAt)

        jdbcTemplate.update(sql, params)
        return appointment
    }

    fun findByRequestId(requestId: String): ServiceRequestAppointment? {
        val sql = "SELECT * FROM service_request_appointment WHERE request_id = :requestId"
        val params = MapSqlParameterSource("requestId", requestId)
        return jdbcTemplate.query(sql, params) { rs, _ -> mapAppointment(rs) }.firstOrNull()
    }

    private fun mapAppointment(rs: ResultSet) = ServiceRequestAppointment(
        appointmentId = rs.getString("appointment_id"),
        requestId = rs.getString("request_id"),
        scheduledDate = rs.getDate("scheduled_date").toLocalDate(),
        timeWindow = AppointmentTimeWindow.valueOf(rs.getString("time_window")),
        startTime = rs.getTime("start_time")?.toLocalTime(),
        endTime = rs.getTime("end_time")?.toLocalTime(),
        technicianId = rs.getString("technician_id"),
        status = AppointmentStatus.valueOf(rs.getString("status")),
        customerNotified = rs.getBoolean("customer_notified"),
        notes = rs.getString("notes"),
        createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
    )
}

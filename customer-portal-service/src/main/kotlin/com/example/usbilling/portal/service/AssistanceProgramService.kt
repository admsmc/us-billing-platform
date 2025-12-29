package com.example.usbilling.portal.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.time.LocalDate

@Service
class AssistanceProgramService(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${customer-portal.customer-service-url}")
    private val customerServiceUrl: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val customerClient: WebClient by lazy {
        webClientBuilder.baseUrl(customerServiceUrl).build()
    }

    /**
     * Get available assistance programs for a utility.
     */
    fun getAvailablePrograms(utilityId: String): List<AssistanceProgram> {
        logger.info("Fetching available assistance programs for utility: $utilityId")

        return try {
            val response = customerClient
                .get()
                .uri("/utilities/{utilityId}/assistance-programs?active=true", utilityId)
                .retrieve()
                .bodyToMono(Map::class.java)
                .block()

            @Suppress("UNCHECKED_CAST")
            val programs = response?.get("programs") as? List<Map<String, Any>> ?: emptyList()

            programs.map { program ->
                AssistanceProgram(
                    programId = program["programId"] as String,
                    programCode = program["programCode"] as String,
                    programName = program["programName"] as String,
                    programType = program["programType"] as String,
                    benefitType = program["benefitType"] as String,
                    benefitValue = (program["benefitValue"] as? Number)?.toDouble(),
                    eligibilityCriteria = program["eligibilityCriteria"] as String,
                    requiredDocuments = program["requiredDocuments"] as? String,
                    requiresVerification = program["requiresVerification"] as Boolean,
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch available programs", e)
            emptyList()
        }
    }

    /**
     * Apply for an assistance program.
     */
    fun applyForProgram(
        customerId: String,
        utilityId: String,
        accountId: String,
        programId: String,
        effectiveFrom: LocalDate,
    ): ApplicationResult {
        logger.info("Customer $customerId applying for program $programId")

        return try {
            val response = customerClient
                .post()
                .uri("/utilities/{utilityId}/assistance-programs/{programId}/apply", utilityId, programId)
                .bodyValue(
                    mapOf(
                        "customerId" to customerId,
                        "accountId" to accountId,
                        "effectiveFrom" to effectiveFrom.toString(),
                    ),
                )
                .retrieve()
                .bodyToMono(Map::class.java)
                .block()

            ApplicationResult.success(
                enrollmentId = response?.get("enrollmentId") as? String ?: "",
                message = "Application submitted successfully",
            )
        } catch (e: Exception) {
            logger.error("Failed to apply for program", e)
            ApplicationResult.failure("Failed to apply: ${e.message}")
        }
    }

    /**
     * Get customer's program enrollments.
     */
    fun getEnrollments(customerId: String, utilityId: String): List<ProgramEnrollment> {
        logger.info("Fetching enrollments for customer: $customerId")

        return try {
            val response = customerClient
                .get()
                .uri("/utilities/{utilityId}/customers/{customerId}/program-enrollments", utilityId, customerId)
                .retrieve()
                .bodyToMono(Map::class.java)
                .block()

            @Suppress("UNCHECKED_CAST")
            val enrollments = response?.get("enrollments") as? List<Map<String, Any>> ?: emptyList()

            enrollments.map { enrollment ->
                ProgramEnrollment(
                    enrollmentId = enrollment["enrollmentId"] as String,
                    programId = enrollment["programId"] as String,
                    programName = enrollment["programName"] as String,
                    programType = enrollment["programType"] as String,
                    status = enrollment["status"] as String,
                    effectiveFrom = LocalDate.parse(enrollment["effectiveFrom"] as String),
                    effectiveTo = (enrollment["effectiveTo"] as? String)?.let { LocalDate.parse(it) },
                    requiresVerification = enrollment["requiresVerification"] as Boolean,
                    verificationStatus = enrollment["verificationStatus"] as? String,
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch enrollments", e)
            emptyList()
        }
    }

    /**
     * Upload verification document for an enrollment.
     */
    fun uploadVerificationDocument(
        enrollmentId: String,
        utilityId: String,
        documentId: String,
    ): ApplicationResult {
        logger.info("Uploading verification document for enrollment: $enrollmentId")

        return try {
            customerClient
                .post()
                .uri(
                    "/utilities/{utilityId}/program-enrollments/{enrollmentId}/documents",
                    utilityId,
                    enrollmentId,
                )
                .bodyValue(
                    mapOf(
                        "documentId" to documentId,
                    ),
                )
                .retrieve()
                .bodyToMono(Map::class.java)
                .block()

            ApplicationResult.success(
                enrollmentId = enrollmentId,
                message = "Document uploaded successfully. Your application is under review.",
            )
        } catch (e: Exception) {
            logger.error("Failed to upload verification document", e)
            ApplicationResult.failure("Failed to upload document: ${e.message}")
        }
    }

    /**
     * Cancel a program enrollment.
     */
    fun cancelEnrollment(
        enrollmentId: String,
        utilityId: String,
        reason: String,
    ): ApplicationResult {
        logger.info("Canceling enrollment: $enrollmentId")

        return try {
            customerClient
                .put()
                .uri("/utilities/{utilityId}/program-enrollments/{enrollmentId}/cancel", utilityId, enrollmentId)
                .bodyValue(
                    mapOf(
                        "reason" to reason,
                    ),
                )
                .retrieve()
                .bodyToMono(Map::class.java)
                .block()

            ApplicationResult.success(
                enrollmentId = enrollmentId,
                message = "Enrollment cancelled successfully",
            )
        } catch (e: Exception) {
            logger.error("Failed to cancel enrollment", e)
            ApplicationResult.failure("Failed to cancel: ${e.message}")
        }
    }
}

data class AssistanceProgram(
    val programId: String,
    val programCode: String,
    val programName: String,
    val programType: String,
    val benefitType: String,
    val benefitValue: Double?,
    val eligibilityCriteria: String,
    val requiredDocuments: String?,
    val requiresVerification: Boolean,
)

data class ProgramEnrollment(
    val enrollmentId: String,
    val programId: String,
    val programName: String,
    val programType: String,
    val status: String,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
    val requiresVerification: Boolean,
    val verificationStatus: String?,
)

sealed class ApplicationResult {
    abstract val success: Boolean
    abstract val message: String

    data class Success(
        val enrollmentId: String,
        override val message: String,
    ) : ApplicationResult() {
        override val success: Boolean = true
    }

    data class Failure(
        override val message: String,
    ) : ApplicationResult() {
        override val success: Boolean = false
    }

    companion object {
        fun success(enrollmentId: String, message: String) = Success(enrollmentId, message)
        fun failure(message: String) = Failure(message)
    }
}

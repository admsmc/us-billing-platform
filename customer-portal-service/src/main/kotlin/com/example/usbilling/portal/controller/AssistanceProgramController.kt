package com.example.usbilling.portal.controller

import com.example.usbilling.portal.service.ApplicationResult
import com.example.usbilling.portal.service.AssistanceProgram
import com.example.usbilling.portal.service.AssistanceProgramService
import com.example.usbilling.portal.service.ProgramEnrollment
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@RestController
@RequestMapping("/api/customers/me/assistance-programs")
class AssistanceProgramController(
    private val assistanceProgramService: AssistanceProgramService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * GET /api/customers/me/assistance-programs/available
     * Get available assistance programs for customer's utility.
     */
    @GetMapping("/available")
    fun getAvailablePrograms(
        @RequestHeader("X-Utility-Id") utilityId: String,
    ): ResponseEntity<AvailableProgramsResponse> {
        logger.info("Fetching available assistance programs")

        val programs = assistanceProgramService.getAvailablePrograms(utilityId)

        return ResponseEntity.ok(
            AvailableProgramsResponse(
                programs = programs,
            ),
        )
    }

    /**
     * POST /api/customers/me/assistance-programs/{programId}/apply
     * Apply for an assistance program.
     */
    @PostMapping("/{programId}/apply")
    fun applyForProgram(
        @PathVariable programId: String,
        @RequestHeader("X-Customer-Id") customerId: String,
        @RequestHeader("X-Utility-Id") utilityId: String,
        @RequestBody request: ApplyForProgramRequest,
    ): ResponseEntity<ApplyForProgramResponse> {
        logger.info("Customer $customerId applying for program $programId")

        val result = assistanceProgramService.applyForProgram(
            customerId = customerId,
            utilityId = utilityId,
            accountId = request.accountId,
            programId = programId,
            effectiveFrom = request.effectiveFrom ?: LocalDate.now(),
        )

        return when (result) {
            is ApplicationResult.Success -> ResponseEntity.ok(
                ApplyForProgramResponse(
                    success = true,
                    enrollmentId = result.enrollmentId,
                    message = result.message,
                ),
            )

            is ApplicationResult.Failure -> ResponseEntity.badRequest().body(
                ApplyForProgramResponse(
                    success = false,
                    enrollmentId = null,
                    message = result.message,
                ),
            )
        }
    }

    /**
     * GET /api/customers/me/assistance-programs/enrollments
     * Get customer's program enrollments.
     */
    @GetMapping("/enrollments")
    fun getEnrollments(
        @RequestHeader("X-Customer-Id") customerId: String,
        @RequestHeader("X-Utility-Id") utilityId: String,
    ): ResponseEntity<EnrollmentsResponse> {
        logger.info("Fetching enrollments for customer $customerId")

        val enrollments = assistanceProgramService.getEnrollments(customerId, utilityId)

        return ResponseEntity.ok(
            EnrollmentsResponse(
                enrollments = enrollments,
            ),
        )
    }

    /**
     * POST /api/customers/me/assistance-programs/enrollments/{enrollmentId}/documents
     * Upload verification document for an enrollment.
     */
    @PostMapping("/enrollments/{enrollmentId}/documents")
    fun uploadVerificationDocument(
        @PathVariable enrollmentId: String,
        @RequestHeader("X-Utility-Id") utilityId: String,
        @RequestBody request: UploadDocumentRequest,
    ): ResponseEntity<UploadDocumentResponse> {
        logger.info("Uploading verification document for enrollment $enrollmentId")

        val result = assistanceProgramService.uploadVerificationDocument(
            enrollmentId = enrollmentId,
            utilityId = utilityId,
            documentId = request.documentId,
        )

        return when (result) {
            is ApplicationResult.Success -> ResponseEntity.ok(
                UploadDocumentResponse(
                    success = true,
                    message = result.message,
                ),
            )

            is ApplicationResult.Failure -> ResponseEntity.badRequest().body(
                UploadDocumentResponse(
                    success = false,
                    message = result.message,
                ),
            )
        }
    }

    /**
     * DELETE /api/customers/me/assistance-programs/enrollments/{enrollmentId}
     * Cancel a program enrollment.
     */
    @DeleteMapping("/enrollments/{enrollmentId}")
    fun cancelEnrollment(
        @PathVariable enrollmentId: String,
        @RequestHeader("X-Utility-Id") utilityId: String,
        @RequestBody request: CancelEnrollmentRequest,
    ): ResponseEntity<CancelEnrollmentResponse> {
        logger.info("Canceling enrollment $enrollmentId")

        val result = assistanceProgramService.cancelEnrollment(
            enrollmentId = enrollmentId,
            utilityId = utilityId,
            reason = request.reason,
        )

        return when (result) {
            is ApplicationResult.Success -> ResponseEntity.ok(
                CancelEnrollmentResponse(
                    success = true,
                    message = result.message,
                ),
            )

            is ApplicationResult.Failure -> ResponseEntity.badRequest().body(
                CancelEnrollmentResponse(
                    success = false,
                    message = result.message,
                ),
            )
        }
    }
}

// Request/Response DTOs

data class AvailableProgramsResponse(
    val programs: List<AssistanceProgram>,
)

data class ApplyForProgramRequest(
    val accountId: String,
    val effectiveFrom: LocalDate? = null,
)

data class ApplyForProgramResponse(
    val success: Boolean,
    val enrollmentId: String?,
    val message: String,
)

data class EnrollmentsResponse(
    val enrollments: List<ProgramEnrollment>,
)

data class UploadDocumentRequest(
    val documentId: String,
)

data class UploadDocumentResponse(
    val success: Boolean,
    val message: String,
)

data class CancelEnrollmentRequest(
    val reason: String,
)

data class CancelEnrollmentResponse(
    val success: Boolean,
    val message: String,
)

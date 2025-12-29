package com.example.usbilling.billing.validation

import com.example.usbilling.billing.model.MeterReadPair
import com.example.usbilling.billing.model.ReadingType
import java.time.temporal.ChronoUnit

/**
 * Validator for usage/meter read data.
 * Validates meter readings for anomalies and provides estimation for missing/invalid reads.
 */
object UsageValidator {

    /**
     * Validate a list of meter read pairs.
     * Checks for common issues like negative usage, rollover detection, and timing anomalies.
     *
     * @param reads List of meter read pairs to validate
     * @return ValidationResult with any warnings or errors found
     */
    fun validate(reads: List<MeterReadPair>): ValidationResult {
        val warnings = mutableListOf<ValidationWarning>()
        val errors = mutableListOf<ValidationError>()

        for (read in reads) {
            // Check for negative usage (without rollover)
            val consumption = read.calculateConsumption()
            if (consumption < 0.0) {
                errors.add(
                    ValidationError(
                        meterId = read.meterId,
                        errorType = ValidationErrorType.NEGATIVE_USAGE,
                        message = "Meter ${read.meterId} has negative consumption: $consumption",
                    ),
                )
            }

            // Check for suspiciously high usage (10x normal)
            if (consumption > 100_000.0) {
                warnings.add(
                    ValidationWarning(
                        meterId = read.meterId,
                        warningType = ValidationWarningType.HIGH_USAGE,
                        message = "Meter ${read.meterId} has unusually high consumption: $consumption",
                    ),
                )
            }

            // Check for zero usage (possible meter malfunction)
            if (consumption == 0.0) {
                warnings.add(
                    ValidationWarning(
                        meterId = read.meterId,
                        warningType = ValidationWarningType.ZERO_USAGE,
                        message = "Meter ${read.meterId} has zero consumption",
                    ),
                )
            }

            // Check for timing issues (start read after end read)
            if (read.startRead.readDate.isAfter(read.endRead.readDate)) {
                errors.add(
                    ValidationError(
                        meterId = read.meterId,
                        errorType = ValidationErrorType.INVALID_TIMESTAMP,
                        message = "Meter ${read.meterId} start read date is after end read",
                    ),
                )
            }

            // Check for very short period (less than 25 days for monthly billing)
            val daysBetween = ChronoUnit.DAYS.between(
                read.startRead.readDate,
                read.endRead.readDate,
            )
            if (daysBetween < 25) {
                warnings.add(
                    ValidationWarning(
                        meterId = read.meterId,
                        warningType = ValidationWarningType.SHORT_PERIOD,
                        message = "Meter ${read.meterId} has short read period: $daysBetween days",
                    ),
                )
            }

            // Check for very long period (more than 35 days for monthly billing)
            if (daysBetween > 35) {
                warnings.add(
                    ValidationWarning(
                        meterId = read.meterId,
                        warningType = ValidationWarningType.LONG_PERIOD,
                        message = "Meter ${read.meterId} has long read period: $daysBetween days",
                    ),
                )
            }

            // Check for potential rollover detection (large jump that seems like rollover)
            val rawDiff = read.endRead.readingValue - read.startRead.readingValue
            if (rawDiff < 0.0 && consumption > 50_000.0) {
                warnings.add(
                    ValidationWarning(
                        meterId = read.meterId,
                        warningType = ValidationWarningType.POSSIBLE_ROLLOVER,
                        message = "Meter ${read.meterId} may have rolled over (consumption: $consumption)",
                    ),
                )
            }
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            warnings = warnings,
            errors = errors,
        )
    }

    /**
     * Estimate missing or invalid meter reads using historical usage data.
     * Uses simple average-based estimation. In production, more sophisticated
     * algorithms could be used (seasonal adjustment, day-of-week patterns, etc.).
     *
     * @param reads List of meter read pairs (may contain invalid reads)
     * @param historicalUsage Historical consumption values for this meter
     * @return List of meter read pairs with estimated values for missing/invalid reads
     */
    fun estimateIfMissing(
        reads: List<MeterReadPair>,
        historicalUsage: List<Double>,
    ): List<MeterReadPair> {
        if (historicalUsage.isEmpty()) {
            // No history available, return original reads
            return reads
        }

        val validation = validate(reads)
        val averageUsage = historicalUsage.average()

        return reads.map { read ->
            // If read has errors, estimate the end read value
            val hasErrors = validation.errors.any { it.meterId == read.meterId }

            if (hasErrors) {
                // Estimate end read based on average historical usage
                val estimatedEndValue = read.startRead.readingValue + averageUsage
                read.copy(
                    endRead = read.endRead.copy(
                        readingValue = estimatedEndValue,
                        readingType = ReadingType.ESTIMATED,
                    ),
                )
            } else {
                read
            }
        }
    }

    /**
     * Estimate usage based on same-period last year.
     * Useful for seasonal businesses or weather-dependent usage.
     *
     * @param currentPeriodStart Start of current billing period
     * @param historicalUsageByPeriod Map of period identifiers to historical usage
     * @return Estimated usage for current period
     */
    fun estimateFromPriorYear(
        currentPeriodStart: java.time.LocalDate,
        historicalUsageByPeriod: Map<String, Double>,
    ): Double? {
        val priorYearKey = currentPeriodStart.minusYears(1).toString()
        return historicalUsageByPeriod[priorYearKey]
    }

    /**
     * Calculate usage deviation from expected based on historical average.
     *
     * @param actualUsage Actual measured usage
     * @param historicalUsage Historical usage values
     * @return Deviation percentage (positive = above average, negative = below average)
     */
    fun calculateDeviation(actualUsage: Double, historicalUsage: List<Double>): Double? {
        if (historicalUsage.isEmpty()) return null

        val average = historicalUsage.average()
        if (average == 0.0) return null

        return ((actualUsage - average) / average) * 100.0
    }
}

/**
 * Result of meter read validation.
 *
 * @property isValid True if no errors were found
 * @property warnings List of non-critical warnings
 * @property errors List of critical errors
 */
data class ValidationResult(
    val isValid: Boolean,
    val warnings: List<ValidationWarning>,
    val errors: List<ValidationError>,
) {
    /**
     * Check if there are any warnings.
     */
    fun hasWarnings(): Boolean = warnings.isNotEmpty()

    /**
     * Check if there are any errors.
     */
    fun hasErrors(): Boolean = errors.isNotEmpty()

    /**
     * Get all issues (warnings + errors) as strings.
     */
    fun allIssues(): List<String> = warnings.map { it.message } + errors.map { it.message }
}

/**
 * Validation warning (non-critical).
 *
 * @property meterId Meter identifier
 * @property warningType Type of warning
 * @property message Human-readable warning message
 */
data class ValidationWarning(
    val meterId: String,
    val warningType: ValidationWarningType,
    val message: String,
)

/**
 * Types of validation warnings.
 */
enum class ValidationWarningType {
    HIGH_USAGE,
    ZERO_USAGE,
    SHORT_PERIOD,
    LONG_PERIOD,
    POSSIBLE_ROLLOVER,
}

/**
 * Validation error (critical).
 *
 * @property meterId Meter identifier
 * @property errorType Type of error
 * @property message Human-readable error message
 */
data class ValidationError(
    val meterId: String,
    val errorType: ValidationErrorType,
    val message: String,
)

/**
 * Types of validation errors.
 */
enum class ValidationErrorType {
    NEGATIVE_USAGE,
    INVALID_TIMESTAMP,
    MISSING_READ,
    DUPLICATE_READ,
}

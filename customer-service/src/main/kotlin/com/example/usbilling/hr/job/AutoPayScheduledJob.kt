package com.example.usbilling.hr.job

import com.example.usbilling.hr.service.AutoPayService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * Scheduled job to process auto-pay enrollments daily.
 * Runs at 2 AM daily to process payments due that day.
 */
@Component
class AutoPayScheduledJob(
    private val autoPayService: AutoPayService,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Process auto-pay enrollments for today.
     * Runs daily at 2:00 AM.
     */
    @Scheduled(cron = "0 0 2 * * *")
    fun processDaily() {
        val today = LocalDate.now()
        logger.info("Starting daily auto-pay processing for: $today")

        try {
            autoPayService.processAutoPayForDate(today)
            logger.info("Completed daily auto-pay processing for: $today")
        } catch (e: Exception) {
            logger.error("Error during daily auto-pay processing", e)
        }
    }
}

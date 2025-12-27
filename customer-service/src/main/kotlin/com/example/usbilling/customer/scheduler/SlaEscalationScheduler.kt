package com.example.usbilling.customer.scheduler

import com.example.usbilling.customer.service.SlaMonitoringService
import com.example.usbilling.shared.UtilityId
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Scheduled task to monitor SLA compliance and auto-escalate cases.
 * Runs every 5 minutes to check for cases breaching SLA thresholds.
 */
@Component
class SlaEscalationScheduler(
    private val slaMonitoringService: SlaMonitoringService,
) {
    
    private val logger = LoggerFactory.getLogger(javaClass)
    
    /**
     * Check SLA compliance every 5 minutes.
     * In production, would iterate over all active utilities.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000) // Every 5 minutes, start after 1 minute
    fun checkSlaCompliance() {
        logger.info("Starting SLA compliance check")
        
        try {
            // In production, would query for all active utilities
            // For now, using a placeholder utility ID
            val utilities = listOf(UtilityId("util-001"))
            
            for (utilityId in utilities) {
                try {
                    val result = slaMonitoringService.checkAndEscalateCases(utilityId)
                    
                    if (result.casesEscalated > 0) {
                        logger.info(
                            "SLA check complete for $utilityId: " +
                            "${result.casesEscalated} cases escalated out of ${result.casesChecked} checked"
                        )
                    }
                    
                    if (result.errors.isNotEmpty()) {
                        logger.warn("SLA check encountered ${result.errors.size} errors for $utilityId")
                        result.errors.forEach { error ->
                            logger.warn("  - $error")
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Failed to check SLA for utility $utilityId", e)
                }
            }
            
            logger.info("SLA compliance check completed")
        } catch (e: Exception) {
            logger.error("SLA compliance check failed", e)
        }
    }
}

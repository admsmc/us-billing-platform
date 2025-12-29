package com.example.usbilling.notification.listener

import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * Case Event Listener - handles case lifecycle notifications.
 *
 * Listens for case events and sends appropriate notifications to customers:
 * - CASE_CREATED → Send case number and acknowledgment
 * - CASE_STATUS_CHANGED → Send status update
 * - CASE_RESOLVED → Send resolution details
 * - CASE_NOTE_ADDED → Send note update (if customer-visible)
 */
@Component
class CaseEventListener(
    // private val notificationService: NotificationService,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["case-events"],
        groupId = "notification-service",
        containerFactory = "kafkaListenerContainerFactory",
    )
    fun handleCaseEvent(event: String) {
        logger.info("Received case event: $event")

        // Parse event and send appropriate notification
        // In production, deserialize JSON event and route based on event type

        /*
        Example events:
        {
          "eventType": "CASE_CREATED",
          "caseId": "...",
          "caseNumber": "CASE-2025-001234",
          "customerId": "...",
          "title": "...",
          "category": "BILLING"
        }

        {
          "eventType": "CASE_STATUS_CHANGED",
          "caseId": "...",
          "caseNumber": "CASE-2025-001234",
          "customerId": "...",
          "fromStatus": "OPEN",
          "toStatus": "IN_PROGRESS"
        }

        {
          "eventType": "CASE_RESOLVED",
          "caseId": "...",
          "caseNumber": "CASE-2025-001234",
          "customerId": "...",
          "resolutionNotes": "..."
        }
         */

        // TODO: Implement notification sending logic
        // notificationService.sendCaseCreatedNotification(...)
        // notificationService.sendCaseStatusChangedNotification(...)
        // notificationService.sendCaseResolvedNotification(...)
    }

    /**
     * Send case created notification.
     *
     * Template: "Your case #{caseNumber} has been created. We'll review it and get back to you shortly."
     */
    private fun sendCaseCreatedNotification(
        customerId: String,
        caseNumber: String,
        title: String,
        category: String,
    ) {
        logger.info("Sending case created notification for case $caseNumber to customer $customerId")
        // notificationService.send(...)
    }

    /**
     * Send case status changed notification.
     *
     * Template: "Your case #{caseNumber} status has been updated to {status}."
     */
    private fun sendCaseStatusChangedNotification(
        customerId: String,
        caseNumber: String,
        fromStatus: String,
        toStatus: String,
    ) {
        logger.info("Sending status change notification for case $caseNumber: $fromStatus -> $toStatus")
        // notificationService.send(...)
    }

    /**
     * Send case resolved notification.
     *
     * Template: "Your case #{caseNumber} has been resolved. Resolution: {resolutionNotes}"
     */
    private fun sendCaseResolvedNotification(
        customerId: String,
        caseNumber: String,
        resolutionNotes: String,
    ) {
        logger.info("Sending case resolved notification for case $caseNumber to customer $customerId")
        // notificationService.send(...)
    }

    /**
     * Send case note added notification (customer-visible notes only).
     *
     * Template: "A new update has been added to your case #{caseNumber}."
     */
    private fun sendCaseNoteAddedNotification(
        customerId: String,
        caseNumber: String,
        noteText: String,
    ) {
        logger.info("Sending case note notification for case $caseNumber to customer $customerId")
        // notificationService.send(...)
    }
}

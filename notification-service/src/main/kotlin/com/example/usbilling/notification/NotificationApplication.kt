package com.example.usbilling.notification

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Notification Service - Multi-channel notification delivery.
 * 
 * Provides notification capabilities for:
 * - Email notifications (SendGrid)
 * - SMS notifications (Twilio)
 * - Push notifications (FCM - Phase 3)
 * - Template rendering (Mustache)
 * - Notification queuing (RabbitMQ)
 * - Event-driven triggers (Kafka)
 * 
 * Port: 8091
 */
@SpringBootApplication(scanBasePackages = ["com.example.usbilling.notification", "com.example.usbilling.web"])
@EnableKafka
@EnableScheduling
class NotificationApplication

fun main(args: Array<String>) {
    runApplication<NotificationApplication>(*args)
}

package com.example.usbilling.orchestrator.config

import com.example.usbilling.messaging.jobs.BillingComputationJobRouting
import org.springframework.amqp.core.*
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * RabbitMQ configuration for billing computation results.
 * Configures queues for receiving bill computation completed events.
 */
@Configuration
class BillingRabbitConfiguration {

    @Bean
    fun billingJobsExchange(): TopicExchange {
        return TopicExchange(BillingComputationJobRouting.EXCHANGE, true, false)
    }

    @Bean
    fun billComputedQueue(): Queue {
        return QueueBuilder.durable(BillingComputationJobRouting.BILL_COMPUTED)
            .build()
    }

    @Bean
    fun billComputedBinding(
        billComputedQueue: Queue,
        billingJobsExchange: TopicExchange
    ): Binding {
        return BindingBuilder
            .bind(billComputedQueue)
            .to(billingJobsExchange)
            .with(BillingComputationJobRouting.BILL_COMPUTED)
    }
}

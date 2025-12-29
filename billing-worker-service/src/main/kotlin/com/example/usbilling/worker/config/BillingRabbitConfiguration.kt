package com.example.usbilling.worker.config

import com.example.usbilling.messaging.jobs.BillingComputationJobRouting
import org.springframework.amqp.core.*
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * RabbitMQ configuration for billing computation jobs.
 * Configures queues, exchanges, and bindings for the worker service.
 */
@Configuration
class BillingRabbitConfiguration {

    @Bean
    fun billingJobsExchange(): TopicExchange {
        return TopicExchange(BillingComputationJobRouting.EXCHANGE, true, false)
    }

    @Bean
    fun billingComputeQueue(): Queue {
        return QueueBuilder.durable(BillingComputationJobRouting.COMPUTE_BILL)
            .withArgument("x-dead-letter-exchange", BillingComputationJobRouting.EXCHANGE)
            .withArgument("x-dead-letter-routing-key", BillingComputationJobRouting.RETRY_30S)
            .build()
    }

    @Bean
    fun billingComputeBinding(
        billingComputeQueue: Queue,
        billingJobsExchange: TopicExchange
    ): Binding {
        return BindingBuilder
            .bind(billingComputeQueue)
            .to(billingJobsExchange)
            .with(BillingComputationJobRouting.COMPUTE_BILL)
    }

    // Retry queues with escalating delays
    @Bean
    fun billingRetry30sQueue(): Queue {
        return QueueBuilder.durable(BillingComputationJobRouting.RETRY_30S)
            .withArgument("x-message-ttl", 30000) // 30 seconds
            .withArgument("x-dead-letter-exchange", BillingComputationJobRouting.EXCHANGE)
            .withArgument("x-dead-letter-routing-key", BillingComputationJobRouting.COMPUTE_BILL)
            .build()
    }

    @Bean
    fun billingRetry1mQueue(): Queue {
        return QueueBuilder.durable(BillingComputationJobRouting.RETRY_1M)
            .withArgument("x-message-ttl", 60000) // 1 minute
            .withArgument("x-dead-letter-exchange", BillingComputationJobRouting.EXCHANGE)
            .withArgument("x-dead-letter-routing-key", BillingComputationJobRouting.RETRY_30S)
            .build()
    }

    @Bean
    fun billingRetry2mQueue(): Queue {
        return QueueBuilder.durable(BillingComputationJobRouting.RETRY_2M)
            .withArgument("x-message-ttl", 120000) // 2 minutes
            .withArgument("x-dead-letter-exchange", BillingComputationJobRouting.EXCHANGE)
            .withArgument("x-dead-letter-routing-key", BillingComputationJobRouting.RETRY_1M)
            .build()
    }

    @Bean
    fun billingDlqQueue(): Queue {
        return QueueBuilder.durable(BillingComputationJobRouting.DLQ).build()
    }

    @Bean
    fun billingDlqBinding(
        billingDlqQueue: Queue,
        billingJobsExchange: TopicExchange
    ): Binding {
        return BindingBuilder
            .bind(billingDlqQueue)
            .to(billingJobsExchange)
            .with(BillingComputationJobRouting.DLQ)
    }
}

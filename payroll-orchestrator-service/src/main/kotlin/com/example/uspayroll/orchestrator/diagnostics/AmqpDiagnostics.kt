package com.example.uspayroll.orchestrator.diagnostics

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class AmqpDiagnostics(
    private val env: Environment,
    private val connectionFactoryProvider: ObjectProvider<ConnectionFactory>,
    private val rabbitTemplateProvider: ObjectProvider<RabbitTemplate>,
) {

    private val logger = LoggerFactory.getLogger(AmqpDiagnostics::class.java)

    @PostConstruct
    fun log() {
        logger.info(
            "diag.amqp.props spring.rabbitmq.host={} orchestrator.jobs.rabbit.enabled={} orchestrator.outbox.rabbit.relay.enabled={} orchestrator.payrun.finalizer.enabled={}",
            env.getProperty("spring.rabbitmq.host"),
            env.getProperty("orchestrator.jobs.rabbit.enabled"),
            env.getProperty("orchestrator.outbox.rabbit.relay.enabled"),
            env.getProperty("orchestrator.payrun.finalizer.enabled"),
        )

        logger.info(
            "diag.amqp.beans connectionFactoryPresent={} rabbitTemplatePresent={}",
            connectionFactoryProvider.ifAvailable != null,
            rabbitTemplateProvider.ifAvailable != null,
        )
    }
}

package com.example.usbilling.orchestrator.config

import org.springframework.context.annotation.Configuration
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories
import org.springframework.transaction.annotation.EnableTransactionManagement

@Configuration
@EnableJdbcRepositories(basePackages = ["com.example.usbilling.orchestrator.domain"])
@EnableTransactionManagement
class JdbcConfiguration

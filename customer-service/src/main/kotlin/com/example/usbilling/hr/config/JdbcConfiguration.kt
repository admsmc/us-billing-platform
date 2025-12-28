package com.example.usbilling.hr.config

import org.springframework.context.annotation.Configuration
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories

/**
 * Spring Data JDBC configuration for customer service.
 */
@Configuration
@EnableJdbcRepositories(basePackages = ["com.example.usbilling.hr.repository"])
class JdbcConfiguration

package com.example.usbilling.tax.config

import org.springframework.context.annotation.Configuration
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories

@Configuration
@EnableJdbcRepositories(basePackages = ["com.example.usbilling.tax.repository"])
class JdbcConfiguration

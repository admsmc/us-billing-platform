package com.example.usbilling.orchestrator.web

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/** orchestrator-service request logging filter (shared implementation lives in web-core). */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class RequestLoggingFilter : com.example.uspayroll.web.RequestLoggingFilter()

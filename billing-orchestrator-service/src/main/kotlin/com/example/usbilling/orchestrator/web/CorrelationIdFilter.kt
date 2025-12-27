package com.example.usbilling.orchestrator.web

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/** orchestrator-service correlation ID filter (shared implementation lives in web-core). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : com.example.usbilling.web.CorrelationIdFilter()

package com.example.usbilling.tax.http

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/** tax-service correlation ID filter (shared implementation lives in web-core). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : com.example.usbilling.web.CorrelationIdFilter()

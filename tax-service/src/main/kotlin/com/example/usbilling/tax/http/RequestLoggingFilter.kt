package com.example.usbilling.tax.http

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/** tax-service request logging filter (shared implementation lives in web-core). */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class RequestLoggingFilter : com.example.usbilling.web.RequestLoggingFilter()

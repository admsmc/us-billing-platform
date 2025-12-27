package com.example.usbilling.hr.web

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/** hr-service request logging filter (shared implementation lives in web-core). */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class RequestLoggingFilter : com.example.usbilling.web.RequestLoggingFilter()

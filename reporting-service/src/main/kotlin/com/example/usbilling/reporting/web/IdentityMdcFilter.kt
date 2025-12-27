package com.example.usbilling.reporting.web

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/** reporting-service identity MDC filter (shared implementation lives in web-core). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class IdentityMdcFilter : com.example.usbilling.web.IdentityMdcFilter()

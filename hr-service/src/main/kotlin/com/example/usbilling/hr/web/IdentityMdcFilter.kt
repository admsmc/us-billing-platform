package com.example.usbilling.hr.web

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/** hr-service identity MDC filter (shared implementation lives in web-core). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class IdentityMdcFilter : com.example.uspayroll.web.IdentityMdcFilter()

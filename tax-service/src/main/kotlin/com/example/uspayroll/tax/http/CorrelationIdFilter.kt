package com.example.uspayroll.tax.http

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/** tax-service correlation ID filter (shared implementation lives in web-core). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : com.example.uspayroll.web.CorrelationIdFilter()

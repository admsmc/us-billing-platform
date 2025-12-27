package com.example.usbilling.worker.web

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/** worker-service correlation ID filter (shared implementation lives in web-core). */
@Component("workerCorrelationIdFilter")
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : com.example.uspayroll.web.CorrelationIdFilter()

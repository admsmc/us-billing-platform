package com.example.uspayroll.worker.web

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/** worker-service request logging filter (shared implementation lives in web-core). */
@Component("workerRequestLoggingFilter")
@Order(Ordered.LOWEST_PRECEDENCE)
class RequestLoggingFilter : com.example.uspayroll.web.RequestLoggingFilter()

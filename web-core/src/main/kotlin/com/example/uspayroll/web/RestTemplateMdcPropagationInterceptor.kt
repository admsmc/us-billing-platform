package com.example.uspayroll.web

import org.slf4j.MDC
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse

/**
 * Propagates correlation + identity headers from MDC to downstream HTTP calls.
 *
 * This enables end-to-end tracing even when internal services call each other.
 */
class RestTemplateMdcPropagationInterceptor : ClientHttpRequestInterceptor {

    override fun intercept(request: HttpRequest, body: ByteArray, execution: ClientHttpRequestExecution): ClientHttpResponse {
        fun copy(mdcKey: String, headerName: String) {
            val value = MDC.get(mdcKey)
            if (!value.isNullOrBlank() && !request.headers.containsKey(headerName)) {
                request.headers.add(headerName, value)
            }
        }

        copy(WebMdcKeys.CORRELATION_ID, WebHeaders.CORRELATION_ID)
        copy(WebMdcKeys.PRINCIPAL_SUB, WebHeaders.PRINCIPAL_SUB)
        copy(WebMdcKeys.PRINCIPAL_SCOPE, WebHeaders.PRINCIPAL_SCOPE)
        copy(WebMdcKeys.EMPLOYER_ID, WebHeaders.EMPLOYER_ID)

        return execution.execute(request, body)
    }
}

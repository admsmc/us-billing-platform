package com.example.uspayroll.tax.http

import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
@Component("taxProblemDetailsAdvice")
class ProblemDetailsAdvice : com.example.uspayroll.web.ProblemDetailsExceptionHandler()

package com.example.uspayroll.hr.web

import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
@Component("hrProblemDetailsAdvice")
class ProblemDetailsAdvice : com.example.uspayroll.web.ProblemDetailsExceptionHandler()

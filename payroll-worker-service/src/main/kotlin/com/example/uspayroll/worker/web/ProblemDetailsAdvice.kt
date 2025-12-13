package com.example.uspayroll.worker.web

import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
@Component("workerProblemDetailsAdvice")
class ProblemDetailsAdvice : com.example.uspayroll.web.ProblemDetailsExceptionHandler()

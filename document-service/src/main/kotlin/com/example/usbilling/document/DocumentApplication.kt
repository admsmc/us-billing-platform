package com.example.usbilling.document

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Document Service - PDF generation and document storage.
 * 
 * Provides document capabilities for:
 * - Bill PDF generation
 * - Document storage (local filesystem or S3)
 * - Document retrieval and download
 * - Template rendering
 * 
 * Port: 8094
 */
@SpringBootApplication(scanBasePackages = ["com.example.usbilling.document", "com.example.usbilling.web"])
class DocumentApplication

fun main(args: Array<String>) {
    runApplication<DocumentApplication>(*args)
}

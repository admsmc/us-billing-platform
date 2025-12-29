package com.example.usbilling.document.controller

import com.example.usbilling.document.generator.BillPdfGenerator
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/documents")
class DocumentController(
    private val billPdfGenerator: BillPdfGenerator,
) {

    @PostMapping("/bills/pdf")
    fun generateBillPdf(@RequestBody billData: Map<String, Any>): ResponseEntity<ByteArray> {
        val pdfBytes = billPdfGenerator.generateBillPdf(billData)

        val billNumber = billData["billNumber"] as? String ?: "unknown"
        val filename = "bill-$billNumber.pdf"

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=$filename")
            .contentType(MediaType.APPLICATION_PDF)
            .body(pdfBytes)
    }
}

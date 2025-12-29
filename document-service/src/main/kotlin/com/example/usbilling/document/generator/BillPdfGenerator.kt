package com.example.usbilling.document.generator

import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.time.LocalDate

@Component
class BillPdfGenerator {

    fun generateBillPdf(billData: Map<String, Any>): ByteArray {
        val outputStream = ByteArrayOutputStream()
        val pdfWriter = PdfWriter(outputStream)
        val pdfDocument = PdfDocument(pdfWriter)
        val document = Document(pdfDocument)

        // Add header
        addHeader(document, billData)

        // Add account information
        addAccountInfo(document, billData)

        // Add billing period and due date
        addBillingInfo(document, billData)

        // Add charges table
        addChargesTable(document, billData)

        // Add usage summary
        addUsageSummary(document, billData)

        // Add payment information
        addPaymentInfo(document, billData)

        // Add QR code (placeholder for now)
        // addQRCode(document, billData)

        document.close()
        return outputStream.toByteArray()
    }

    private fun addHeader(document: Document, billData: Map<String, Any>) {
        val utilityName = billData["utilityName"] as? String ?: "Utility Company"
        val billNumber = billData["billNumber"] as? String ?: "N/A"

        document.add(
            Paragraph(utilityName)
                .setFontSize(20f)
                .setBold()
                .setTextAlignment(TextAlignment.CENTER),
        )

        document.add(
            Paragraph("UTILITY BILL")
                .setFontSize(16f)
                .setBold()
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginBottom(20f),
        )

        document.add(
            Paragraph("Bill Number: $billNumber")
                .setFontSize(10f)
                .setTextAlignment(TextAlignment.RIGHT),
        )
    }

    private fun addAccountInfo(document: Document, billData: Map<String, Any>) {
        val customerName = billData["customerName"] as? String ?: "N/A"
        val accountNumber = billData["accountNumber"] as? String ?: "N/A"
        val serviceAddress = billData["serviceAddress"] as? String ?: "N/A"

        val table = Table(2)
        table.setWidth(UnitValue.createPercentValue(100f))
        table.setMarginBottom(15f)

        table.addCell(createCell("Account Information", isHeader = true, colspan = 2))
        table.addCell(createCell("Account Holder:"))
        table.addCell(createCell(customerName))
        table.addCell(createCell("Account Number:"))
        table.addCell(createCell(accountNumber))
        table.addCell(createCell("Service Address:"))
        table.addCell(createCell(serviceAddress))

        document.add(table)
    }

    private fun addBillingInfo(document: Document, billData: Map<String, Any>) {
        val billDate = billData["billDate"] as? String ?: LocalDate.now().toString()
        val dueDate = billData["dueDate"] as? String ?: LocalDate.now().plusDays(30).toString()
        val periodStart = billData["periodStart"] as? String ?: "N/A"
        val periodEnd = billData["periodEnd"] as? String ?: "N/A"

        val table = Table(4)
        table.setWidth(UnitValue.createPercentValue(100f))
        table.setMarginBottom(15f)

        table.addCell(createCell("Bill Date", isHeader = true))
        table.addCell(createCell("Due Date", isHeader = true))
        table.addCell(createCell("Period Start", isHeader = true))
        table.addCell(createCell("Period End", isHeader = true))

        table.addCell(createCell(billDate))
        table.addCell(createCell(dueDate))
        table.addCell(createCell(periodStart))
        table.addCell(createCell(periodEnd))

        document.add(table)
    }

    private fun addChargesTable(document: Document, billData: Map<String, Any>) {
        val charges = (billData["charges"] as? List<Map<String, Any>>) ?: emptyList()

        val table = Table(3)
        table.setWidth(UnitValue.createPercentValue(100f))
        table.setMarginBottom(15f)

        table.addCell(createCell("Description", isHeader = true))
        table.addCell(createCell("Quantity", isHeader = true))
        table.addCell(createCell("Amount", isHeader = true))

        charges.forEach { charge ->
            val description = charge["description"] as? String ?: ""
            val quantity = charge["quantity"] as? String ?: ""
            val amount = charge["amount"] as? Number ?: 0.0

            table.addCell(createCell(description))
            table.addCell(createCell(quantity))
            table.addCell(createCell(String.format("$%.2f", amount.toDouble())))
        }

        val totalAmount = billData["totalAmount"] as? Number ?: 0.0
        table.addCell(createCell("Total Amount Due", isHeader = true, colspan = 2))
        table.addCell(
            createCell(String.format("$%.2f", totalAmount.toDouble()), isHeader = true)
                .setBackgroundColor(ColorConstants.LIGHT_GRAY),
        )

        document.add(table)
    }

    private fun addUsageSummary(document: Document, billData: Map<String, Any>) {
        val currentUsage = billData["currentUsage"] as? Number ?: 0
        val previousUsage = billData["previousUsage"] as? Number ?: 0
        val usageUnit = billData["usageUnit"] as? String ?: "kWh"

        val table = Table(3)
        table.setWidth(UnitValue.createPercentValue(100f))
        table.setMarginBottom(15f)

        table.addCell(createCell("Usage Summary", isHeader = true, colspan = 3))
        table.addCell(createCell("Previous Reading:"))
        table.addCell(createCell("$previousUsage $usageUnit", colspan = 2))
        table.addCell(createCell("Current Reading:"))
        table.addCell(createCell("$currentUsage $usageUnit", colspan = 2))
        table.addCell(createCell("Usage This Period:"))
        table.addCell(
            createCell("${currentUsage.toInt() - previousUsage.toInt()} $usageUnit", colspan = 2)
                .setBold(),
        )

        document.add(table)
    }

    private fun addPaymentInfo(document: Document, billData: Map<String, Any>) {
        val paymentMethods = """
            Payment Methods:
            - Online: www.utility-portal.com
            - Phone: 1-800-555-1234
            - Mail: Payments Department, PO Box 12345, City, ST 12345
            - In Person: Visit any authorized payment location
        """.trimIndent()

        document.add(
            Paragraph(paymentMethods)
                .setFontSize(9f)
                .setMarginTop(20f),
        )
    }

    private fun createCell(content: String, isHeader: Boolean = false, colspan: Int = 1): Cell {
        val cell = Cell(1, colspan).add(Paragraph(content))

        if (isHeader) {
            cell.setBackgroundColor(ColorConstants.LIGHT_GRAY)
            cell.setBold()
        }

        cell.setPadding(5f)
        return cell
    }
}

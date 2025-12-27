package com.example.usbilling.worker.tools

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

/**
 * Simple command-line helper to manually replay garnishment withholding
 * callbacks to HR.
 *
 * Usage:
 *   kotlin -classpath ... com.example.uspayroll.worker.tools.GarnishmentWithholdingReplayCli \
 *     <hrBaseUrl> <employerId> <employeeId> <jsonFilePath>
 *
 * Where <jsonFilePath> contains a JSON body compatible with
 * GarnishmentWithholdingRequest.
 */
object GarnishmentWithholdingReplayCli {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 4) {
            System.err.println("Usage: GarnishmentWithholdingReplayCli <hrBaseUrl> <employerId> <employeeId> <jsonFilePath>")
            System.exit(1)
        }

        val hrBaseUrl = args[0].trimEnd('/')
        val employerId = args[1]
        val employeeId = args[2]
        val jsonPath = Path.of(args[3])

        if (!Files.exists(jsonPath)) {
            System.err.println("JSON file not found at $jsonPath")
            System.exit(1)
        }

        val body = Files.readString(jsonPath)
        val url = "$hrBaseUrl/employers/$employerId/employees/$employeeId/garnishments/withholdings"

        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        println("POSTing withholding payload from $jsonPath to $url ...")
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        println("HR responded with status ${response.statusCode()}")
        if (response.body().isNotBlank()) {
            println("Response body:\n${response.body()}")
        }

        if (response.statusCode() !in 200..299) {
            System.exit(1)
        }
    }
}

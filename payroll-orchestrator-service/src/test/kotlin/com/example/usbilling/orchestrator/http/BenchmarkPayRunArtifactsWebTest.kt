package com.example.usbilling.orchestrator.http

import com.example.usbilling.orchestrator.support.StubClientsTestConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(StubClientsTestConfig::class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@TestPropertySource(
    properties = [
        "orchestrator.benchmarks.enabled=true",
        "orchestrator.benchmarks.token=secret-token",
        "orchestrator.benchmarks.header-name=X-Benchmark-Token",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class BenchmarkPayRunArtifactsWebTest(
    @Autowired private val mockMvc: MockMvc,
) {

    @Test
    fun `renderPayStatements returns ProblemDetails on unauthorized`() {
        val employerId = "emp-1"
        val payRunId = "run-1"

        val body = """
            {
              "serializeJson": true,
              "generateCsv": false
            }
        """.trimIndent()

        val mvcResult = mockMvc.perform(
            post("/benchmarks/employers/$employerId/payruns/$payRunId/render-pay-statements")
                .contentType(MediaType.APPLICATION_JSON)
                // Intentionally omit or send wrong token header
                .header("X-Benchmark-Token", "wrong-token")
                .content(body),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andReturn()

        val responseBody = mvcResult.response.contentAsString
        // Basic sanity checks on ProblemDetails shape
        // (status and errorCode fields should be present; we avoid asserting exact content).
        assert(responseBody.contains("\"status\""))
        assert(responseBody.contains("\"errorCode\""))
    }
}

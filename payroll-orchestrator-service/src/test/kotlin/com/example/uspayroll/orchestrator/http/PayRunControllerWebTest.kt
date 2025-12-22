package com.example.uspayroll.orchestrator.http

import com.example.uspayroll.orchestrator.support.StubClientsTestConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestConstructor
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(StubClientsTestConfig::class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PayRunControllerWebTest(
    @Autowired private val mockMvc: MockMvc,
) {

    @Test
    fun `startFinalize rejects mismatched header and body idempotency keys`() {
        val employerId = "emp-1"

        val body = """
            {
              "payPeriodId": "pp-1",
              "employeeIds": ["e-1"],
              "idempotencyKey": "body-key"
            }
        """.trimIndent()

        mockMvc.perform(
            post("/employers/$employerId/payruns/finalize")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "header-key")
                .content(body),
        )
            .andExpect(status().isBadRequest)
    }
}
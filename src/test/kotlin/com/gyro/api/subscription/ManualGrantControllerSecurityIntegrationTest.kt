package com.gyro.api.subscription

import com.gyro.api.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.*

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ],
)
class ManualGrantControllerSecurityIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
) {

    @Test
    fun `anonymous user cannot access manual grant admin endpoints`() {
        adminRequests().forEach { request ->
            mockMvc.perform(request)
                .andExpect(status().isUnauthorized)
        }
    }

    @Test
    fun `normal user cannot access manual grant admin endpoints`() {
        val userId = UUID.randomUUID()

        adminRequests().forEach { request ->
            mockMvc.perform(request.with(authentication(testAuthentication(userId, "ROLE_USER"))))
                .andExpect(status().isForbidden)
        }
    }

    private fun adminRequests(): List<MockHttpServletRequestBuilder> {
        val grantId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        return listOf(
            post("/api/v1/admin/grants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreatePayload()),
            post("/api/v1/admin/grants/$grantId/extend")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"additionalDays": 15, "reason": "security test"}"""),
            post("/api/v1/admin/grants/$grantId/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason": "security test"}"""),
            get("/api/v1/admin/grants/user/$userId"),
        )
    }

    private fun validCreatePayload(): String {
        return """
            {
              "userId": "${UUID.randomUUID()}",
              "planId": 1,
              "durationDays": 30,
              "reason": "TESTER_ACCESS",
              "reasonNote": "security test"
            }
        """.trimIndent()
    }

    private fun testAuthentication(userId: UUID, role: String): UsernamePasswordAuthenticationToken {
        return UsernamePasswordAuthenticationToken(
            userId.toString(),
            null,
            listOf(SimpleGrantedAuthority(role)),
        )
    }
}

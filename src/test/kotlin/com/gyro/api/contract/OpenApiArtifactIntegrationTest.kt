package com.gyro.api.contract

import com.gyro.api.TestcontainersConfiguration
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.notification.telegram-enabled=true",
        "app.notification.telegram-linking-enabled=true",
        "app.notification.telegram-bot-token=contract-test-token",
        "app.notification.telegram-chat-id=contract-test-chat",
        "app.notification.telegram-bot-username=gyro_contract_test",
        "app.notification.telegram-webhook-secret=contract-test-secret",
        "app.notification.telegram-webhook-url=https://example.invalid/telegram/webhook",
    ],
)
class OpenApiArtifactIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {
    @Test
    fun `runtime contract matches the canonical OpenAPI artifact`() {
        val first = canonicalOpenApi()
        val second = canonicalOpenApi()

        assertArrayEquals(first, second, "Two requests from the same application must produce identical contracts")

        val artifact = Path.of("openapi", "openapi.json")
        if (System.getProperty("gyro.openapi.update", "false").toBoolean()) {
            Files.createDirectories(artifact.parent)
            Files.write(
                artifact,
                first,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            return
        }

        assertTrue(Files.isRegularFile(artifact), "Run ./gradlew generateOpenApi to create $artifact")
        assertArrayEquals(
            Files.readAllBytes(artifact),
            first,
            "The committed contract is stale; run ./gradlew generateOpenApi and review the API diff",
        )
    }

    private fun canonicalOpenApi(): ByteArray {
        val response = mockMvc.get("/api-docs")
            .andExpect { status { isOk() } }
            .andReturn()
            .response
            .contentAsByteArray

        val canonicalMapper = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build()
        val document = objectMapper.readValue(response, Any::class.java)
        return (canonicalMapper.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n")
            .toByteArray(Charsets.UTF_8)
    }
}

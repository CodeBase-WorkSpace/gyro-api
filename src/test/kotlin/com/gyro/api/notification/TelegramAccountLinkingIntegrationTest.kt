package com.gyro.api.notification

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.notification.application.TelegramAccountLinkingService
import com.gyro.api.notification.application.TelegramLinkConsumption
import com.gyro.api.notification.application.TelegramLinkState
import com.gyro.api.notification.application.delivery.TelegramLinkDataCleanupService
import com.gyro.api.notification.domain.AdapterClassification
import com.gyro.api.notification.domain.AdapterOutcome
import com.gyro.api.notification.domain.AdapterResult
import com.gyro.api.notification.domain.NotificationType
import com.gyro.api.notification.infrastructure.delivery.TelegramBotSendResult
import com.gyro.api.notification.infrastructure.delivery.TelegramDirectMessageSender
import com.gyro.api.notification.infrastructure.persistence.NotificationIntentRepository
import com.gyro.api.notification.infrastructure.persistence.NotificationTelegramEndpointRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class, NotificationAdapterTestConfiguration::class, TelegramLinkSenderTestConfiguration::class)
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@SpringBootTest(properties = [
    "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
    "app.security.verification-code-pepper=test-pepper",
    "app.rate-limit.enabled=false",
    "app.billing.lifecycle.jobs-enabled=false",
    "app.notification.jobs-enabled=false",
    "app.notification.telegram-enabled=true",
    "app.notification.telegram-bot-token=test-bot-token",
    "app.notification.telegram-chat-id=@test-channel",
    "app.notification.telegram-linking-enabled=true",
    "app.notification.telegram-bot-username=gyro_link_test_bot",
    "app.notification.telegram-webhook-secret=test-webhook-secret",
    "app.notification.telegram-webhook-url=https://api.example.com/api/v1/integrations/telegram/webhook",
    "spring.data.redis.host=localhost",
    "spring.data.redis.port=6379",
])
class TelegramAccountLinkingIntegrationTest(
    @Autowired private val linking: TelegramAccountLinkingService,
    @Autowired private val cleanupService: TelegramLinkDataCleanupService,
    @Autowired private val endpoints: NotificationTelegramEndpointRepository,
    @Autowired private val intents: NotificationIntentRepository,
    @Autowired private val sender: RecordingTelegramLinkSender,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val mockMvc: MockMvc,
) {
    private val users = mutableSetOf<UUID>()

    @AfterEach
    fun cleanup() {
        users.forEach { jdbc.update("delete from notification_intents where user_id = ?", it) }
        jdbc.update("delete from notification_telegram_link_codes where bot_identity = 'gyro_link_test_bot'")
        users.forEach { jdbc.update("delete from notification_telegram_link_tokens where user_id = ?", it) }
        users.forEach { jdbc.update("delete from notification_telegram_endpoints where user_id = ?", it) }
        jdbc.update("delete from notification_telegram_webhook_updates where bot_identity = 'gyro_link_test_bot'")
        users.forEach { jdbc.update("delete from users where id = ?", it) }
        users.clear()
        sender.reset()
    }

    @Test
    fun `bot code is hashed encrypted single use and binds to authenticated user`() {
        val userId = createUser()
        val challenge = requireNotNull(linking.createCode(1234567, 1234567))

        assertFalse(jdbc.queryForObject(
            "select exists(select 1 from notification_telegram_link_codes where code_hash = ?)",
            Boolean::class.java,
            challenge.code.replace("-", ""),
        )!!)
        assertEquals(TelegramLinkState.UNLINKED, linking.status(userId).state)
        assertEquals(TelegramLinkConsumption.LINKED, linking.consume(userId, challenge.code).outcome)
        assertEquals(TelegramLinkConsumption.INVALID, linking.consume(userId, challenge.code).outcome)

        val endpoint = requireNotNull(endpoints.findByUserIdAndBotIdentity(userId, "gyro_link_test_bot"))
        assertNotEquals("1234567", endpoint.telegramUserCiphertext)
        assertNotEquals("1234567", endpoint.chatIdCiphertext)
        assertEquals("1234567", linking.active(userId)?.chatId)
        assertEquals(TelegramLinkState.LINKED, linking.status(userId).state)
    }

    @Test
    fun `expired malformed and superseded codes are rejected`() {
        val userId = createUser()
        val first = requireNotNull(linking.createCode(222, 222))
        val second = requireNotNull(linking.createCode(222, 222))
        assertEquals(TelegramLinkConsumption.INVALID, linking.consume(userId, first.code).outcome)
        assertEquals(TelegramLinkConsumption.INVALID, linking.consume(userId, "IIII-OOOO").outcome)

        jdbc.update(
            "update notification_telegram_link_codes set created_at = now() - interval '2 seconds', expires_at = now() - interval '1 second' where consumed_at is null",
        )
        assertEquals(TelegramLinkConsumption.INVALID, linking.consume(userId, second.code).outcome)
    }

    @Test
    fun `Telegram identity already owned by another account cannot be claimed`() {
        val first = createUser()
        val second = createUser()
        val firstCode = requireNotNull(linking.createCode(777, 777))
        assertEquals(TelegramLinkConsumption.LINKED, linking.consume(first, firstCode.code).outcome)

        val secondCode = requireNotNull(linking.createCode(777, 777))
        assertEquals(TelegramLinkConsumption.COLLISION, linking.consume(second, secondCode.code).outcome)
        assertEquals(null, endpoints.findByUserIdAndBotIdentity(second, "gyro_link_test_bot"))
    }

    @Test
    fun `concurrent claims consume one code exactly once`() {
        val first = createUser()
        val second = createUser()
        val code = requireNotNull(linking.createCode(555, 555)).code
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(first, second).map { userId ->
                executor.submit<TelegramLinkConsumption> {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS)
                    linking.consume(userId, code).outcome
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(
                setOf(TelegramLinkConsumption.LINKED, TelegramLinkConsumption.INVALID),
                results.map { it.get(10, TimeUnit.SECONDS) }.toSet(),
            )
            assertEquals(1, endpoints.count())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `cleanup removes expired unclaimed codes early and retains recent grace records`() {
        val userId = createUser()
        val unclaimed = requireNotNull(linking.createCode(888, 888))
        val consumed = requireNotNull(linking.createCode(889, 889))
        val recent = requireNotNull(linking.createCode(890, 890))
        assertEquals(TelegramLinkConsumption.LINKED, linking.consume(userId, consumed.code).outcome)
        jdbc.update(
            "update notification_telegram_link_codes set created_at = now() - interval '3 hours', expires_at = now() - interval '2 hours', updated_at = now() - interval '2 hours' where id = ?",
            unclaimed.id,
        )
        jdbc.update(
            "update notification_telegram_link_codes set created_at = now() - interval '8 days 10 minutes', expires_at = now() - interval '8 days', consumed_at = now() - interval '8 days', updated_at = now() - interval '8 days' where id = ?",
            consumed.id,
        )
        jdbc.update(
            "update notification_telegram_link_codes set created_at = now() - interval '40 minutes', expires_at = now() - interval '30 minutes', updated_at = now() - interval '30 minutes' where id = ?",
            recent.id,
        )
        jdbc.update(
            """insert into notification_telegram_webhook_updates(bot_identity, update_id, received_at)
                values ('gyro_link_test_bot', 81, now() - interval '8 days'),
                       ('gyro_link_test_bot', 82, now())""",
        )

        val result = cleanupService.purgeBatch()

        assertEquals(1, result.unclaimedCodes)
        assertEquals(1, result.consumedCodes)
        assertEquals(2, result.linkTokens)
        assertEquals(1, result.webhookUpdates)
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from notification_telegram_link_codes where id = ?",
            Int::class.java,
            recent.id,
        ))
    }

    @Test
    fun `webhook sends code and authenticated confirmation completes linking`() {
        val userId = createUser()
        mockMvc.post("/api/v1/integrations/telegram/webhook") {
            contentType = MediaType.APPLICATION_JSON
            header("X-Telegram-Bot-Api-Secret-Token", "wrong")
            content = "not-json"
        }.andExpect { status { isUnauthorized() } }

        mockMvc.post("/api/v1/integrations/telegram/webhook") {
            contentType = MediaType.APPLICATION_JSON
            header("X-Telegram-Bot-Api-Secret-Token", "test-webhook-secret")
            content = updateJson(12, 900, "private", "/start link")
        }.andExpect { status { isNoContent() } }

        assertEquals("900", sender.chatId)
        val code = requireNotNull(CODE_IN_MESSAGE.find(sender.text.orEmpty())?.value)
        repeat(2) {
            mockMvc.post("/api/v1/integrations/telegram/webhook") {
                contentType = MediaType.APPLICATION_JSON
                header("X-Telegram-Bot-Api-Secret-Token", "test-webhook-secret")
                content = updateJson(12, 900, "private", "/start link")
            }.andExpect { status { isNoContent() } }
        }
        assertEquals(1, sender.sendCount)

        mockMvc.post("/api/v1/notifications/telegram/link/confirm") {
            with(authentication(userAuthentication(userId)))
            contentType = MediaType.APPLICATION_JSON
            content = """{"code":"$code"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.state") { value("LINKED") }
        }
        assertEquals(TelegramLinkState.LINKED, linking.status(userId).state)
        assertEquals(1, intents.findAllByUserId(userId).count { it.type == NotificationType.TELEGRAM_LINK_CONFIRMATION })

        mockMvc.get("/api/v1/notifications/telegram/status") {
            with(authentication(userAuthentication(userId)))
        }.andExpect { jsonPath("$.state") { value("LINKED") } }
        mockMvc.delete("/api/v1/notifications/telegram/link") {
            with(authentication(userAuthentication(userId)))
        }.andExpect { status { isNoContent() } }
        assertEquals(TelegramLinkState.RELINK_REQUIRED, linking.status(userId).state)
        mockMvc.post("/api/v1/notifications/telegram/link") {
            with(authentication(userAuthentication(userId)))
        }.andExpect { jsonPath("$.url") { value("https://t.me/gyro_link_test_bot?start=link") } }
    }

    private fun createUser(): UUID = UUID.randomUUID().also { userId ->
        users += userId
        jdbc.update(
            """insert into users (id, email, password_hash, role, email_verification_status, phone_verification_status, status, created_at, updated_at)
                values (?, ?, 'hash', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())""",
            userId,
            "telegram-$userId@example.com",
        )
    }

    private fun updateJson(updateId: Long, telegramId: Long, chatType: String, text: String) = """
        {"update_id":$updateId,"message":{"message_id":1,"from":{"id":$telegramId},"chat":{"id":$telegramId,"type":"$chatType"},"text":"$text"}}
    """.trimIndent()

    private fun userAuthentication(userId: UUID) = UsernamePasswordAuthenticationToken(
        userId.toString(), null, listOf(SimpleGrantedAuthority("ROLE_USER")),
    )

    private companion object {
        val CODE_IN_MESSAGE = Regex("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}")
    }
}

@TestConfiguration(proxyBeanMethods = false)
class TelegramLinkSenderTestConfiguration {
    @Bean
    @Primary
    fun telegramLinkSender() = RecordingTelegramLinkSender()
}

class RecordingTelegramLinkSender : TelegramDirectMessageSender {
    var chatId: String? = null
    var text: String? = null
    var sendCount: Int = 0

    override fun send(chatId: String, text: String): TelegramBotSendResult {
        this.chatId = chatId
        this.text = text
        sendCount++
        return TelegramBotSendResult(AdapterResult(AdapterOutcome.SUCCESS, AdapterClassification.LOG_ONLY_SUCCESS))
    }

    fun reset() {
        chatId = null
        text = null
        sendCount = 0
    }
}

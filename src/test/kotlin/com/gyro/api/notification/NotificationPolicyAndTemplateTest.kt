package com.gyro.api.notification

import com.gyro.api.notification.application.policy.NotificationPolicyRegistry
import com.gyro.api.notification.application.template.NotificationTemplateCatalog
import com.gyro.api.notification.domain.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class NotificationPolicyAndTemplateTest {
    private val catalog = NotificationTemplateCatalog()
    private val policies = NotificationPolicyRegistry(catalog)

    @Test
    fun `core probe policy is bounded provider independent and not user configurable`() {
        val policy = policies.policyFor(NotificationType.CORE_PROBE)

        assertEquals(NotificationCategory.MANDATORY_TRANSACTIONAL, policy.category)
        assertEquals(NotificationRouteStrategy.EXPLICIT_CHANNELS, policy.routeStrategy)
        assertEquals(setOf(NotificationChannel.EMAIL), policy.allowedChannels)
        assertEquals("log-only", policy.adapterKey)
        assertEquals(1, policy.retryPolicy.maxAttempts)
        assertEquals(false, policy.userConfigurable)
        assertEquals(false, policy.smsAllowed)
    }

    @Test
    fun `template hash is deterministic and covers rendered content`() {
        val definition = catalog.definitions.first()

        assertEquals(definition.contentHash(), definition.copy().contentHash())
        assertEquals(64, definition.contentHash().length)
        assertNotEquals(definition.contentHash(), definition.copy(plainBody = "Changed").contentHash())
    }

    @Test
    fun `admin announcement policy is push only opt-out and carries free text as public variables`() {
        val policy = policies.policyFor(NotificationType.ADMIN_ANNOUNCEMENT)

        assertEquals(NotificationCategory.OPTIONAL_ANNOUNCEMENTS, policy.category)
        assertEquals(setOf(NotificationChannel.PUSH), policy.allowedChannels)
        assertEquals("web-push", policy.adapterKey)
        assertEquals(true, policy.userConfigurable)
        assertEquals(false, policy.smsAllowed)
        assertEquals(setOf("title", "body"), policy.requiredVariables.keys)
        policy.requiredVariables.values.forEach { rule ->
            assertEquals(TemplateVariableType.TEXT, rule.type)
            assertEquals(TemplateVariableSensitivity.PUBLIC, rule.sensitivity)
            assertEquals(TemplateVariablePersistence.ALLOWED, rule.persistence)
        }

        val template = catalog.find("admin-announcement", 1, NotificationChannel.PUSH, "fa")
        assertEquals("{{title}}", template?.subject)
        assertEquals("{{body}}", template?.plainBody)
    }

    @Test
    fun `coach data nudge is a push only optional announcement with public copy`() {
        val policy = policies.policyFor(NotificationType.COACH_DATA_NUDGE)

        assertEquals(NotificationCategory.OPTIONAL_ANNOUNCEMENTS, policy.category)
        assertEquals(setOf(NotificationChannel.PUSH), policy.allowedChannels)
        assertEquals(NotificationRouteStrategy.EXPLICIT_CHANNELS, policy.routeStrategy)
        assertEquals(true, policy.userConfigurable)
        assertEquals(setOf("body"), policy.requiredVariables.keys)
        assertEquals(TemplateVariableSensitivity.PUBLIC, policy.requiredVariables.getValue("body").sensitivity)
        assertEquals(TemplateVariablePersistence.ALLOWED, policy.requiredVariables.getValue("body").persistence)
    }

    @Test
    fun `payment receipt policy prefers a single verified email then SMS`() {
        val policy = policies.policyFor(NotificationType.PAYMENT_VERIFIED)

        assertEquals(NotificationRouteStrategy.PREFERRED_AVAILABLE, policy.routeStrategy)
        assertEquals(setOf(NotificationChannel.EMAIL, NotificationChannel.SMS), policy.allowedChannels)
        assertEquals("smtp-email", policy.adapterKeys[NotificationChannel.EMAIL])
        assertEquals("sms-ir", policy.adapterKeys[NotificationChannel.SMS])
        assertEquals(false, policy.userConfigurable)
        assertEquals(true, policy.smsAllowed)
    }

    @Test
    fun `premium downgrade is mandatory factual account state communication`() {
        val policy = policies.policyFor(NotificationType.PREMIUM_ACCESS_DOWNGRADED)

        assertEquals(NotificationCategory.MANDATORY_TRANSACTIONAL, policy.category)
        assertEquals(setOf(NotificationChannel.PUSH), policy.allowedChannels)
        assertEquals(NotificationRouteStrategy.EXPLICIT_CHANNELS, policy.routeStrategy)
        assertEquals(false, policy.userConfigurable)
        assertEquals(false, policy.smsAllowed)
    }
}

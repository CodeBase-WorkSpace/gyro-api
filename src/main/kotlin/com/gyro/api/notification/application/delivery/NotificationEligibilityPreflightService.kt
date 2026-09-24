package com.gyro.api.notification.application.delivery

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.application.NotificationPreferenceService
import com.gyro.api.notification.application.NotificationEndpointEligibilityService
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.domain.*
import com.gyro.api.notification.infrastructure.persistence.NotificationDeliveryRepository
import com.gyro.api.notification.infrastructure.persistence.NotificationCadenceRepository
import com.gyro.api.notification.infrastructure.persistence.NotificationIntentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

enum class NotificationPreflightOutcome { PROCEED, DEFERRED, TERMINAL }
data class NotificationPreflightResult(val outcome: NotificationPreflightOutcome, val intentId: UUID)

@Service
class NotificationEligibilityPreflightService(
    private val deliveries: NotificationDeliveryRepository,
    private val intents: NotificationIntentRepository,
    private val preferences: NotificationPreferenceService,
    private val endpoints: NotificationEndpointEligibilityService,
    private val cadence: NotificationCadenceRepository,
    private val time: TimeProvider,
    private val properties: NotificationProperties,
) {
    @Transactional
    fun evaluate(claim: NotificationDeliveryClaim): NotificationPreflightResult {
        val now = time.now()
        val delivery = requireNotNull(deliveries.findById(claim.deliveryId).orElse(null))
        require(delivery.status == NotificationDeliveryStatus.CLAIMED && delivery.claimOwner == claim.owner && delivery.claimToken == claim.token) {
            "Notification delivery ${claim.deliveryId} is no longer owned by this claim"
        }
        val intent = requireNotNull(intents.findById(delivery.intentId).orElse(null))
        if (!endpoints.isEligible(intent.userId, delivery.channel, delivery.endpointReference, delivery.adapterKey)) {
            terminalize(delivery, NotificationDeliveryStatus.PERMANENT_FAILURE, NotificationReason.ENDPOINT_INVALID, now)
            return NotificationPreflightResult(NotificationPreflightOutcome.TERMINAL, intent.id)
        }
        if (
            intent.category == NotificationCategory.MANDATORY_TRANSACTIONAL &&
            delivery.channel != NotificationChannel.PUSH
        ) {
            return NotificationPreflightResult(NotificationPreflightOutcome.PROCEED, intent.id)
        }
        val eligibility = preferences.deliveryEligibility(intent.userId, intent.category)
        if (
            intent.category != NotificationCategory.MANDATORY_TRANSACTIONAL &&
            !eligibility.enabled
        ) {
            terminalize(delivery, NotificationDeliveryStatus.SUPPRESSED, NotificationReason.PREFERENCE_DISABLED, now)
            return NotificationPreflightResult(NotificationPreflightOutcome.TERMINAL, intent.id)
        }
        val quietHoursEnd = if (
            intent.category != NotificationCategory.MANDATORY_TRANSACTIONAL ||
            delivery.channel == NotificationChannel.PUSH
        ) {
            nextAllowedInstant(
                now,
                eligibility.timezone,
                eligibility.quietHoursStart,
                eligibility.quietHoursEnd,
            )
        } else {
            null
        }
        val minimumGapEnd = if (delivery.channel == NotificationChannel.PUSH) {
            cadence.latestDeliveredPushAt(intent.userId)
                ?.plus(properties.pushMinimumGap)
                ?.takeIf { it.isAfter(now) }
        } else {
            null
        }
        val nextAllowed = listOfNotNull(quietHoursEnd, minimumGapEnd).maxOrNull()
            ?: return NotificationPreflightResult(NotificationPreflightOutcome.PROCEED, intent.id)
        if (!nextAllowed.isBefore(delivery.expiresAt)) {
            if (intent.category == NotificationCategory.MANDATORY_TRANSACTIONAL) {
                // A transactional consequence is never discarded. If an unusually late
                // retry leaves no room to defer, delivering is safer than silent expiry.
                return NotificationPreflightResult(NotificationPreflightOutcome.PROCEED, intent.id)
            }
            terminalize(delivery, NotificationDeliveryStatus.EXPIRED, NotificationReason.MISSED_ALLOWED_WINDOW, now)
            return NotificationPreflightResult(NotificationPreflightOutcome.TERMINAL, intent.id)
        }
        delivery.dueAt = nextAllowed
        delivery.status = NotificationDeliveryStatus.PENDING
        delivery.reason = null
        delivery.nextAttemptAt = null
        releaseClaim(delivery, now)
        return NotificationPreflightResult(NotificationPreflightOutcome.DEFERRED, intent.id)
    }

    private fun nextAllowedInstant(now: Instant, timezone: String, start: java.time.LocalTime, end: java.time.LocalTime): Instant? {
        val zonedNow = now.atZone(ZoneId.of(timezone))
        val localTime = zonedNow.toLocalTime()
        val withinQuiet = if (start < end) localTime >= start && localTime < end else localTime >= start || localTime < end
        if (!withinQuiet) return null
        val endDate = if (start < end || localTime < end) zonedNow.toLocalDate() else zonedNow.toLocalDate().plusDays(1)
        return ZonedDateTime.of(endDate, end, zonedNow.zone).toInstant()
    }

    private fun terminalize(delivery: com.gyro.api.notification.infrastructure.persistence.NotificationDeliveryEntity, status: NotificationDeliveryStatus, reason: NotificationReason, now: Instant) {
        delivery.status = status
        delivery.reason = reason
        delivery.contentPurgeAt = now.plus(properties.contentRetention)
        releaseClaim(delivery, now)
    }

    private fun releaseClaim(delivery: com.gyro.api.notification.infrastructure.persistence.NotificationDeliveryEntity, now: Instant) {
        delivery.claimOwner = null
        delivery.claimedAt = null
        delivery.claimExpiresAt = null
        delivery.claimToken = null
        delivery.updatedAt = now
    }
}

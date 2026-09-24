package com.gyro.api.notification.application

import com.gyro.api.notification.application.delivery.NotificationChannelAdapter
import com.gyro.api.notification.domain.NotificationChannel
import com.gyro.api.notification.domain.NotificationPolicy
import com.gyro.api.notification.domain.NotificationReason
import com.gyro.api.notification.domain.NotificationRouteStrategy
import org.springframework.stereotype.Service
import java.util.UUID

data class NotificationRoute(
    val channel: NotificationChannel,
    val endpointReference: String,
    val adapterKey: String,
)

data class NotificationRoutingResult(
    val routes: List<NotificationRoute>,
    val unavailableReason: NotificationReason? = null,
)

/** Resolves the current account endpoint before a delivery is made durable. */
@Service
class NotificationRoutingService(
    private val endpoints: NotificationEndpointEligibilityService,
    adapters: List<NotificationChannelAdapter>,
) {
    private val availableAdapters = adapters.map(NotificationChannelAdapter::adapterKey).toSet()

    fun resolve(userId: UUID, policy: NotificationPolicy): NotificationRoutingResult {
        val candidates = policy.allowedChannels
            .sortedBy { PREFERRED_CHANNEL_ORDER.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
            .map { channel ->
                val adapterKey = requireNotNull(policy.adapterKeys[channel])
                NotificationRoute(channel, endpointReference(channel, adapterKey), adapterKey)
            }

        return when (policy.routeStrategy) {
            NotificationRouteStrategy.EXPLICIT_CHANNELS -> NotificationRoutingResult(candidates)
            NotificationRouteStrategy.ALL_ELIGIBLE -> NotificationRoutingResult(
                candidates.filter { route -> route.adapterKey in availableAdapters && endpoints.isEligible(userId, route.channel, route.endpointReference, route.adapterKey) },
                NotificationReason.NO_VERIFIED_ENDPOINT,
            )
            NotificationRouteStrategy.PREFERRED_AVAILABLE -> resolvePreferred(userId, candidates)
        }
    }

    private fun resolvePreferred(userId: UUID, candidates: List<NotificationRoute>): NotificationRoutingResult {
        val endpointEligible = candidates.filter { route ->
            endpoints.isEligible(userId, route.channel, route.endpointReference, route.adapterKey)
        }
        val route = endpointEligible.firstOrNull { it.adapterKey in availableAdapters }
        return if (route != null) {
            NotificationRoutingResult(listOf(route))
        } else {
            NotificationRoutingResult(
                emptyList(),
                if (endpointEligible.isEmpty()) NotificationReason.NO_VERIFIED_ENDPOINT else NotificationReason.CHANNEL_DISABLED,
            )
        }
    }

    private fun endpointReference(channel: NotificationChannel, adapterKey: String): String {
        if (adapterKey == "log-only") return NotificationEndpointEligibilityService.INTERNAL_CORE_PROBE_REFERENCE
        return when (channel) {
        NotificationChannel.EMAIL -> NotificationEndpointEligibilityService.ACCOUNT_EMAIL_REFERENCE
        NotificationChannel.SMS -> NotificationEndpointEligibilityService.ACCOUNT_PHONE_REFERENCE
        NotificationChannel.PUSH -> ACTIVE_PUSH_ENDPOINT
        else -> "internal:${channel.name.lowercase()}"
        }
    }

    private companion object {
        val PREFERRED_CHANNEL_ORDER = listOf(NotificationChannel.EMAIL, NotificationChannel.SMS)
        const val ACTIVE_PUSH_ENDPOINT = "push:active"
    }
}

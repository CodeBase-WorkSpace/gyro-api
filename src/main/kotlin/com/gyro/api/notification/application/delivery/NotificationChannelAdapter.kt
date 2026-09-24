package com.gyro.api.notification.application.delivery

import com.gyro.api.notification.domain.AdapterResult
import com.gyro.api.notification.domain.RenderedNotification

interface NotificationChannelAdapter {
    val adapterKey: String

    /**
     * Reconciles an incomplete request using [RenderedNotification.providerRequestId]. Adapters backed by a
     * provider status API must return the provider's recorded outcome. A null result means no recorded request
     * was found. Null is safe only when [deliver] passes that same ID to a provider with native idempotency.
     */
    fun reconcile(notification: RenderedNotification): AdapterResult?

    /** Deliveries and retries must pass [RenderedNotification.providerRequestId] unchanged to the provider. */
    fun deliver(notification: RenderedNotification): AdapterResult
}

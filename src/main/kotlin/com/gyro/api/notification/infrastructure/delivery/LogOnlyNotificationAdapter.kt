package com.gyro.api.notification.infrastructure.delivery

import com.gyro.api.notification.application.delivery.NotificationChannelAdapter
import com.gyro.api.notification.domain.AdapterClassification
import com.gyro.api.notification.domain.AdapterOutcome
import com.gyro.api.notification.domain.AdapterResult
import com.gyro.api.notification.domain.RenderedNotification
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class LogOnlyNotificationAdapter : NotificationChannelAdapter {
    override val adapterKey = "log-only"

    override fun reconcile(notification: RenderedNotification): AdapterResult = SUCCESS

    override fun deliver(notification: RenderedNotification): AdapterResult {
        log.info(
            "event=notification_delivery adapter={} outcome=success intentId={} deliveryId={} type={} channel={}",
            adapterKey,
            notification.intentId,
            notification.deliveryId,
            notification.type,
            notification.channel,
        )
        return SUCCESS
    }

    companion object {
        private val log = LoggerFactory.getLogger(LogOnlyNotificationAdapter::class.java)
        private val SUCCESS = AdapterResult(AdapterOutcome.SUCCESS, AdapterClassification.LOG_ONLY_SUCCESS)
    }
}

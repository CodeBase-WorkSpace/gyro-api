package com.gyro.api.notification.infrastructure.delivery

import com.gyro.api.notification.config.NotificationEgressProxyType
import com.gyro.api.notification.config.NotificationProperties
import okhttp3.Credentials
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/** Builds provider clients with proxy settings scoped only to notification delivery adapters. */
internal object NotificationHttpClientFactory {
    fun build(properties: NotificationProperties, timeout: Duration): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .callTimeout(timeout)
            .connectTimeout(timeout)
            .readTimeout(timeout)
            .writeTimeout(timeout)

        when (properties.egressProxyType) {
            NotificationEgressProxyType.NONE -> Unit
            NotificationEgressProxyType.HTTP -> builder.proxy(proxy(Proxy.Type.HTTP, properties))
            NotificationEgressProxyType.SOCKS -> builder.proxy(proxy(Proxy.Type.SOCKS, properties))
        }

        if (properties.egressProxyType == NotificationEgressProxyType.HTTP && properties.egressProxyUsername.isNotBlank()) {
            builder.proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) {
                    null
                } else {
                    response.request.newBuilder()
                        .header(
                            "Proxy-Authorization",
                            Credentials.basic(properties.egressProxyUsername, properties.egressProxyPassword),
                        )
                        .build()
                }
            }
        }

        return withTransmissionTracking(builder.build())
    }

    fun withTransmissionTracking(client: OkHttpClient): OkHttpClient = client.newBuilder()
        .eventListenerFactory { call -> NotificationRequestEventListener(call) }
        .build()

    private fun proxy(type: Proxy.Type, properties: NotificationProperties) =
        Proxy(type, InetSocketAddress(properties.egressProxyHost, properties.egressProxyPort))
}

internal class NotificationRequestTransmission {
    private val bodyStarted = AtomicBoolean(false)

    val mayHaveReachedProvider: Boolean
        get() = bodyStarted.get()

    fun markBodyStarted() {
        bodyStarted.set(true)
    }
}

private class NotificationRequestEventListener(call: Call) : EventListener() {
    private val transmission = call.request().tag(NotificationRequestTransmission::class.java)

    override fun requestBodyStart(call: Call) {
        transmission?.markBodyStarted()
    }
}

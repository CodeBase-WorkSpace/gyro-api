package com.gyro.api.notification.infrastructure.delivery

import com.gyro.api.notification.config.NotificationEgressProxyType
import com.gyro.api.notification.config.NotificationProperties
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Authenticator
import okhttp3.Dns
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.UnknownHostException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NotificationHttpClientFactoryTest {
    @Test
    fun `HTTP proxy is scoped to the built notification client`() {
        val client = NotificationHttpClientFactory.build(
            NotificationProperties(
                egressProxyType = NotificationEgressProxyType.HTTP,
                egressProxyHost = "proxy.local",
                egressProxyPort = 10809,
                egressProxyUsername = "user",
                egressProxyPassword = "password",
            ),
            Duration.ofSeconds(12),
        )

        assertEquals(Proxy.Type.HTTP, client.proxy?.type())
        assertEquals(InetSocketAddress("proxy.local", 10809), client.proxy?.address())
        assertNotNull(client.proxyAuthenticator)
        assertEquals(Duration.ofSeconds(12).toMillis(), client.callTimeoutMillis.toLong())
        assertEquals(emptyList(), client.interceptors)
        assertEquals(emptyList(), client.networkInterceptors)
    }

    @Test
    fun `SOCKS proxy is scoped to the client without authentication`() {
        val client = NotificationHttpClientFactory.build(
            NotificationProperties(
                egressProxyType = NotificationEgressProxyType.SOCKS,
                egressProxyHost = "proxy.local",
                egressProxyPort = 10808,
            ),
            Duration.ofSeconds(12),
        )

        assertEquals(Proxy.Type.SOCKS, client.proxy?.type())
        assertEquals(InetSocketAddress("proxy.local", 10808), client.proxy?.address())
        assertEquals(Authenticator.NONE, client.proxyAuthenticator)
    }

    @Test
    fun `DNS failure occurs before request body dispatch`() {
        val client = NotificationHttpClientFactory.build(NotificationProperties(), Duration.ofSeconds(1))
            .newBuilder()
            .dns(Dns { throw UnknownHostException("provider lookup failed") })
            .build()

        val transmission = failedTransmission(client, "https://push.invalid/delivery")

        assertFalse(transmission.mayHaveReachedProvider)
    }

    @Test
    fun `proxy connection refusal occurs before request body dispatch`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val client = NotificationHttpClientFactory.build(
            NotificationProperties(
                egressProxyType = NotificationEgressProxyType.HTTP,
                egressProxyHost = "127.0.0.1",
                egressProxyPort = closedPort,
            ),
            Duration.ofSeconds(1),
        )

        val transmission = failedTransmission(client, "https://push.invalid/delivery")

        assertFalse(transmission.mayHaveReachedProvider)
    }

    @Test
    fun `proxy authentication rejection occurs before request body dispatch`() {
        val proxy = MockWebServer()
        val rejection = MockResponse.Builder()
            .code(407)
            .addHeader("Proxy-Authenticate", "Basic realm=\"notification-egress\"")
            .build()
        proxy.enqueue(rejection)
        proxy.enqueue(rejection)
        proxy.start()
        try {
            val client = NotificationHttpClientFactory.build(
                NotificationProperties(
                    egressProxyType = NotificationEgressProxyType.HTTP,
                    egressProxyHost = "127.0.0.1",
                    egressProxyPort = proxy.port,
                    egressProxyUsername = "user",
                    egressProxyPassword = "wrong-password",
                ),
                Duration.ofSeconds(1),
            )

            val transmission = failedTransmission(client, "https://push.invalid/delivery")

            assertFalse(transmission.mayHaveReachedProvider)
        } finally {
            proxy.close()
        }
    }

    @Test
    fun `TLS handshake failure occurs before request body dispatch`() {
        val provider = MockWebServer()
        provider.enqueue(MockResponse.Builder().failHandshake().build())
        provider.start()
        try {
            val client = NotificationHttpClientFactory.build(NotificationProperties(), Duration.ofSeconds(1))

            val transmission = failedTransmission(client, "https://127.0.0.1:${provider.port}/delivery")

            assertFalse(transmission.mayHaveReachedProvider)
        } finally {
            provider.close()
        }
    }

    @Test
    fun `disconnect during request body makes the provider outcome uncertain`() {
        val provider = MockWebServer()
        provider.enqueue(
            MockResponse.Builder()
                .onRequestBody(SocketEffect.CloseSocket())
                .build(),
        )
        provider.start()
        try {
            val client = NotificationHttpClientFactory.build(NotificationProperties(), Duration.ofSeconds(1))

            val transmission = failedTransmission(
                client,
                provider.url("/delivery").toString(),
                "payload".repeat(128_000),
            )

            assertTrue(transmission.mayHaveReachedProvider)
        } finally {
            provider.close()
        }
    }

    private fun failedTransmission(
        client: okhttp3.OkHttpClient,
        url: String,
        body: String = "payload",
    ): NotificationRequestTransmission {
        val transmission = NotificationRequestTransmission()
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody())
            .tag(NotificationRequestTransmission::class.java, transmission)
            .build()

        assertFailsWith<IOException> { client.newCall(request).execute().close() }
        return transmission
    }
}

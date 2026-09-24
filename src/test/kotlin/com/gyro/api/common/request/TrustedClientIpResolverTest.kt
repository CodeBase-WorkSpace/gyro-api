package com.gyro.api.common.request

import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class TrustedClientIpResolverTest {
    private val resolver = TrustedClientIpResolver(
        ClientIpProperties(listOf("172.16.0.0/12", "2001:db8:1::/48")),
    )

    @Test
    fun `direct request cannot spoof its address with forwarding headers`() {
        val request = requestFrom("198.51.100.20").apply {
            addHeader("X-Forwarded-For", "203.0.113.12")
            addHeader(TrustedClientIpResolver.CLIENT_IP_HEADER, "203.0.113.13")
        }

        assertEquals("198.51.100.20", resolver.resolve(request))
    }

    @Test
    fun `trusted reverse proxy can supply its overwritten client address`() {
        val request = requestFrom("172.20.0.4").apply {
            addHeader(TrustedClientIpResolver.CLIENT_IP_HEADER, "203.0.113.12")
        }

        assertEquals("203.0.113.12", resolver.resolve(request))
    }

    @Test
    fun `multiple proxy hops are rejected because the private header must contain one address`() {
        val request = requestFrom("172.20.0.4").apply {
            addHeader(TrustedClientIpResolver.CLIENT_IP_HEADER, "203.0.113.12, 172.20.0.3")
        }

        assertEquals("172.20.0.4", resolver.resolve(request))
    }

    @Test
    fun `missing or malformed private header falls back to trusted proxy peer`() {
        assertEquals("172.20.0.4", resolver.resolve(requestFrom("172.20.0.4")))

        val malformed = requestFrom("172.20.0.4").apply {
            addHeader(TrustedClientIpResolver.CLIENT_IP_HEADER, "not-an-ip")
        }
        assertEquals("172.20.0.4", resolver.resolve(malformed))
    }

    @Test
    fun `trusted IPv6 proxy resolves an IPv6 client`() {
        val request = requestFrom("2001:db8:1::4").apply {
            addHeader(TrustedClientIpResolver.CLIENT_IP_HEADER, "2001:db8:2::12")
        }

        assertEquals("2001:db8:2:0:0:0:0:12", resolver.resolve(request))
    }

    private fun requestFrom(address: String) = MockHttpServletRequest().apply { remoteAddr = address }
}

package com.gyro.api.common.request

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.net.InetAddress

@ConfigurationProperties(prefix = "app.http")
data class ClientIpProperties(
    val trustedProxyCidrs: List<String> = emptyList(),
)

/**
 * Resolves a client address without treating caller-controlled forwarding headers as authoritative.
 *
 * The private header is accepted only from explicitly configured proxy networks. The deployment
 * proxy must overwrite it with its direct peer address; requests from every other source use the
 * servlet peer address, even when they supply forwarding headers.
 */
@Component
class TrustedClientIpResolver(properties: ClientIpProperties) {
    private val trustedProxies = properties.trustedProxyCidrs.map(CidrBlock::parse)

    fun resolve(request: HttpServletRequest): String {
        val peer = parseIpLiteral(request.remoteAddr)
            ?: return request.remoteAddr?.trim().takeUnless { it.isNullOrEmpty() } ?: UNKNOWN_ADDRESS
        if (trustedProxies.none { it.contains(peer) }) return peer.hostAddress

        return request.getHeader(CLIENT_IP_HEADER)
            ?.trim()
            ?.let(::parseIpLiteral)
            ?.hostAddress
            ?: peer.hostAddress
    }

    private data class CidrBlock(
        val network: ByteArray,
        val prefixLength: Int,
    ) {
        fun contains(address: InetAddress): Boolean {
            val candidate = address.address
            if (candidate.size != network.size) return false

            val completeBytes = prefixLength / BITS_PER_BYTE
            for (index in 0 until completeBytes) {
                if (candidate[index] != network[index]) return false
            }
            val remainingBits = prefixLength % BITS_PER_BYTE
            if (remainingBits == 0) return true
            val mask = (0xFF shl (BITS_PER_BYTE - remainingBits)) and 0xFF
            return (candidate[completeBytes].toInt() and mask) ==
                (network[completeBytes].toInt() and mask)
        }

        companion object {
            fun parse(value: String): CidrBlock {
                val parts = value.trim().split('/', limit = 2)
                val address = parseIpLiteral(parts[0])
                    ?: throw IllegalArgumentException("Trusted proxy CIDR contains an invalid IP address: $value")
                val bitCount = address.address.size * BITS_PER_BYTE
                val prefix = parts.getOrNull(1)?.toIntOrNull() ?: bitCount
                require(prefix in 0..bitCount) { "Trusted proxy CIDR has an invalid prefix length: $value" }
                return CidrBlock(address.address, prefix)
            }
        }
    }

    companion object {
        const val CLIENT_IP_HEADER = "X-Gyro-Client-IP"
        private const val UNKNOWN_ADDRESS = "unknown"
        private const val BITS_PER_BYTE = 8

        private fun parseIpLiteral(value: String?): InetAddress? {
            val candidate = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
            if (candidate.any(Char::isWhitespace) || ',' in candidate || '%' in candidate) return null
            if (':' !in candidate) {
                val octets = candidate.split('.')
                if (octets.size != 4 || octets.any { it.isEmpty() || it.length > 3 || it.any { char -> !char.isDigit() } || it.toInt() !in 0..255 }) {
                    return null
                }
            } else if (candidate.any { it !in "0123456789abcdefABCDEF:." }) {
                return null
            }
            return runCatching { InetAddress.getByName(candidate) }.getOrNull()
        }
    }
}

package com.gyro.api.auth.infrastructure

import com.gyro.api.auth.application.verification.VerificationChannel
import com.gyro.api.auth.application.verification.VerificationDeliveryPort
import com.gyro.api.auth.application.verification.VerificationDeliveryRequest
import com.gyro.api.auth.config.VerificationDeliveryProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.util.Locale

@Component
@ConditionalOnProperty(
    prefix = "gyro.verification",
    name = ["email-provider"],
    havingValue = "log-only",
    matchIfMissing = true
)
class LogOnlyEmailDeliveryAdapter(
    private val properties: VerificationDeliveryProperties,
    private val environment: Environment,
) : VerificationDeliveryPort {
    override val channel = VerificationChannel.EMAIL

    override fun deliver(request: VerificationDeliveryRequest) {
        require(request.channel == channel) {
            "LogOnlyEmailDeliveryAdapter cannot deliver channel=${request.channel}."
        }

        logger.atInfo()
            .addKeyValue("event", "DEV_VERIFICATION_CODE")
            .addKeyValue("channel", request.channel.name)
            .addKeyValue("identifier", request.identifier.maskEmail())
            .addKeyValue("purpose", request.purpose.name)
            .addKeyValue("code", request.codeForLog())
            .log("DEV_VERIFICATION_CODE")
    }

    private fun VerificationDeliveryRequest.codeForLog(): String? {
        return code.takeIf { properties.allowCodeLogging && environment.codeLoggingProfileActive() }
    }

    private fun Environment.codeLoggingProfileActive(): Boolean {
        return activeProfiles.any { it == "dev" || it == "staging" }
    }

    private fun String.maskEmail(): String {
        val parts = trim().lowercase(Locale.ROOT).split("@", limit = 2)
        if (parts.size != 2) return "***"

        val local = parts[0]
        val domain = parts[1]
        val visiblePrefix = local.take(1).ifBlank { "*" }
        return "$visiblePrefix***@$domain"
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(LogOnlyEmailDeliveryAdapter::class.java)
    }
}

package com.gyro.api.auth.infrastructure

import com.gyro.api.auth.application.IranianPhoneValidator
import com.gyro.api.auth.application.verification.VerificationChannel
import com.gyro.api.auth.application.verification.VerificationDeliveryPort
import com.gyro.api.auth.application.verification.VerificationDeliveryRequest
import com.gyro.api.auth.config.VerificationDeliveryProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "gyro.verification", name = ["sms-provider"], havingValue = "log-only", matchIfMissing = true)
class LogOnlySmsDeliveryAdapter(
    private val properties: VerificationDeliveryProperties,
    private val environment: Environment,
) : VerificationDeliveryPort {
    override val channel = VerificationChannel.SMS

    override fun deliver(request: VerificationDeliveryRequest) {
        require(request.channel == channel) {
            "LogOnlySmsDeliveryAdapter cannot deliver channel=${request.channel}."
        }

        logger.atInfo()
            .addKeyValue("event", "DEV_VERIFICATION_CODE")
            .addKeyValue("channel", request.channel.name)
            .addKeyValue("identifier", request.identifier.maskPhoneNumber())
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

    private fun String.maskPhoneNumber(): String {
        val normalized = runCatching { IranianPhoneValidator.normalize(this) }.getOrElse { trim() }
        if (normalized.isBlank()) return "***"

        val visibleSuffix = normalized.takeLast(4)
        return "***$visibleSuffix"
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(LogOnlySmsDeliveryAdapter::class.java)
    }
}

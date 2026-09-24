package com.gyro.api.auth.application.verification

import com.gyro.api.common.error.DomainException
import com.gyro.api.common.error.ExternalServiceException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class VerificationDeliveryGatewayImpl(
    ports: List<VerificationDeliveryPort>,
) : VerificationDeliveryGateway {
    private val portsByChannel = ports.associateBy { it.channel }

    override fun deliver(
        channel: VerificationChannel,
        identifier: String,
        purpose: VerificationPurpose,
        code: String,
    ) {
        val port = portsByChannel[channel]
        if (port == null) {
            logger.atError()
                .addKeyValue("event", "verification_delivery")
                .addKeyValue("stage", "adapter_missing")
                .addKeyValue("channel", channel.name)
                .addKeyValue("purpose", purpose.name)
                .log("No verification delivery adapter configured.")
            throw ExternalServiceException(channel.serviceName())
        }

        try {
            logger.atInfo()
                .addKeyValue("event", "verification_delivery")
                .addKeyValue("stage", "adapter_started")
                .addKeyValue("channel", channel.name)
                .addKeyValue("purpose", purpose.name)
                .addKeyValue("adapter", port::class.simpleName)
                .log("Sending verification code.")
            port.deliver(
                VerificationDeliveryRequest(
                    channel = channel,
                    identifier = identifier,
                    purpose = purpose,
                    code = code,
                )
            )
            logger.atInfo()
                .addKeyValue("event", "verification_delivery")
                .addKeyValue("stage", "adapter_completed")
                .addKeyValue("channel", channel.name)
                .addKeyValue("purpose", purpose.name)
                .addKeyValue("adapter", port::class.simpleName)
                .log("Verification code sent.")
        } catch (ex: DomainException) {
            throw ex
        } catch (ex: Exception) {
            logger.atError()
                .addKeyValue("event", "verification_delivery")
                .addKeyValue("stage", "adapter_failed")
                .addKeyValue("channel", channel.name)
                .addKeyValue("purpose", purpose.name)
                .addKeyValue("adapter", port::class.simpleName)
                .addKeyValue("exception", ex::class.simpleName)
                .log("Verification delivery adapter failed unexpectedly.", ex)
            throw ExternalServiceException(channel.serviceName())
        }
    }

    private fun VerificationChannel.serviceName(): String {
        return when (this) {
            VerificationChannel.EMAIL -> "Email verification"
            VerificationChannel.SMS -> "SMS verification"
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(VerificationDeliveryGatewayImpl::class.java)
    }
}

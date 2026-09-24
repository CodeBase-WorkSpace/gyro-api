package com.gyro.api.auth.application.verification

import com.gyro.api.common.error.ExternalServiceException
import com.gyro.api.common.error.RateLimitExceededException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VerificationDeliveryGatewayImplTest {
    @Test
    fun `deliver forwards request to matching channel adapter`() {
        val port = RecordingDeliveryPort(VerificationChannel.SMS)
        val gateway = VerificationDeliveryGatewayImpl(listOf(port))

        gateway.deliver(
            channel = VerificationChannel.SMS,
            identifier = "09121234567",
            purpose = VerificationPurpose.SIGNUP,
            code = "123456",
        )

        assertEquals(1, port.requests.size)
        assertEquals(VerificationPurpose.SIGNUP, port.requests.single().purpose)
    }

    @Test
    fun `deliver translates missing channel adapter into external service exception`() {
        val gateway = VerificationDeliveryGatewayImpl(emptyList())

        assertFailsWith<ExternalServiceException> {
            gateway.deliver(
                channel = VerificationChannel.SMS,
                identifier = "09121234567",
                purpose = VerificationPurpose.SIGNUP,
                code = "123456",
            )
        }
    }

    @Test
    fun `deliver translates unexpected adapter failure into external service exception`() {
        val gateway = VerificationDeliveryGatewayImpl(
            listOf(ThrowingDeliveryPort(VerificationChannel.SMS, IllegalStateException("boom")))
        )

        assertFailsWith<ExternalServiceException> {
            gateway.deliver(
                channel = VerificationChannel.SMS,
                identifier = "09121234567",
                purpose = VerificationPurpose.SIGNUP,
                code = "123456",
            )
        }
    }

    @Test
    fun `deliver preserves domain exceptions from adapter`() {
        val gateway = VerificationDeliveryGatewayImpl(
            listOf(ThrowingDeliveryPort(VerificationChannel.SMS, RateLimitExceededException()))
        )

        assertFailsWith<RateLimitExceededException> {
            gateway.deliver(
                channel = VerificationChannel.SMS,
                identifier = "09121234567",
                purpose = VerificationPurpose.SIGNUP,
                code = "123456",
            )
        }
    }

    private class RecordingDeliveryPort(
        override val channel: VerificationChannel,
    ) : VerificationDeliveryPort {
        val requests = mutableListOf<VerificationDeliveryRequest>()

        override fun deliver(request: VerificationDeliveryRequest) {
            requests += request
        }
    }

    private class ThrowingDeliveryPort(
        override val channel: VerificationChannel,
        private val failure: RuntimeException,
    ) : VerificationDeliveryPort {
        override fun deliver(request: VerificationDeliveryRequest) {
            throw failure
        }
    }
}

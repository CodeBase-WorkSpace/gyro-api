package com.gyro.api.auth.infrastructure

import com.gyro.api.auth.application.IranianPhoneValidator
import com.gyro.api.auth.application.verification.VerificationChannel
import com.gyro.api.auth.application.verification.VerificationDeliveryPort
import com.gyro.api.auth.application.verification.VerificationDeliveryRequest
import com.gyro.api.auth.config.VerificationDeliveryProperties
import com.gyro.api.common.error.ExternalServiceException
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.IOException

/** Sends one-time verification codes through Kavenegar's Verify Lookup endpoint. */
@Component
@ConditionalOnProperty(prefix = "gyro.verification", name = ["sms-provider"], havingValue = "kavenegar")
class KavenegarSmsDeliveryAdapter(
    private val properties: VerificationDeliveryProperties,
    private val objectMapper: ObjectMapper,
) : VerificationDeliveryPort {
    override val channel = VerificationChannel.SMS

    private val client = OkHttpClient()

    override fun deliver(request: VerificationDeliveryRequest) {
        require(request.channel == channel) {
            "KavenegarSmsDeliveryAdapter cannot deliver channel=${request.channel}."
        }

        try {
            val config = properties.kavenegar
            val payload = FormBody.Builder()
                .add("receptor", IranianPhoneValidator.normalize(request.identifier))
                .add("token", request.code)
                .add("template", config.templateFor(request.purpose).template)
                .build()
            val httpRequest = Request.Builder()
                .url("${config.baseUrl.trimEnd('/')}/${config.apiKey}/verify/lookup.json")
                .post(payload)
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body?.string()
                if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                    logger.error("Failed to send SMS verification via Kavenegar. status={}", response.code)
                    throw ExternalServiceException("Kavenegar")
                }

                val status = objectMapper.readTree(responseBody).path("return").path("status").asInt()
                if (status != SUCCESS_STATUS) {
                    logger.error("Kavenegar verification API returned unsuccessful status. status={}", status)
                    throw ExternalServiceException("Kavenegar")
                }
            }
        } catch (ex: ExternalServiceException) {
            throw ex
        } catch (ex: IOException) {
            logger.error("Failed to connect to Kavenegar verification API.", ex)
            throw ExternalServiceException("Kavenegar")
        } catch (ex: RuntimeException) {
            logger.error("Failed to process Kavenegar verification API response.", ex)
            throw ExternalServiceException("Kavenegar")
        }
    }

    private companion object {
        private const val SUCCESS_STATUS = 200
        private val logger = LoggerFactory.getLogger(KavenegarSmsDeliveryAdapter::class.java)
    }
}

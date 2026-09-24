package com.gyro.api.auth.infrastructure

import com.gyro.api.auth.application.IranianPhoneValidator
import com.gyro.api.auth.application.verification.VerificationChannel
import com.gyro.api.auth.application.verification.VerificationDeliveryPort
import com.gyro.api.auth.application.verification.VerificationDeliveryRequest
import com.gyro.api.auth.config.VerificationDeliveryProperties
import com.gyro.api.common.error.ExternalServiceException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.IOException

@Component
@ConditionalOnProperty(prefix = "gyro.verification", name = ["sms-provider"], havingValue = "sms-ir")
class SmsProviderDeliveryAdapter(
    private val properties: VerificationDeliveryProperties,
    private val objectMapper: ObjectMapper,
) : VerificationDeliveryPort {
    override val channel = VerificationChannel.SMS

    private val client = OkHttpClient()

    override fun deliver(request: VerificationDeliveryRequest) {
        require(request.channel == channel) {
            "SmsProviderDeliveryAdapter cannot deliver channel=${request.channel}."
        }

        try {
            val config = properties.smsIr
            val template = config.templateFor(request.purpose)
            val payload = objectMapper.writeValueAsString(
                SmsIrVerifyRequest(
                    mobile = IranianPhoneValidator.normalize(request.identifier),
                    templateId = template.templateId,
                    parameters = listOf(
                        SmsIrVerifyParameter(
                            name = template.parameterName,
                            value = request.code,
                        )
                    ),
                )
            )
            val httpRequest = Request.Builder()
                .url("${config.baseUrl.trimEnd('/')}/send/verify")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", JSON_MEDIA_TYPE.toString())
                .addHeader("Accept", "application/json")
                .addHeader("x-api-key", config.apiKey)
                .build()

            client.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body?.string()
                if (!response.isSuccessful) {
                    logger.error(
                        "Failed to send SMS verification via SMS.ir. status={}, body={}",
                        response.code,
                        responseBody,
                    )
                    throw ExternalServiceException("SMS.ir")
                }

                val parsedStatus = responseBody
                    ?.takeIf { it.isNotBlank() }
                    ?.let { objectMapper.readTree(it).get("status")?.asInt() }

                if (parsedStatus != SUCCESS_STATUS) {
                    logger.error(
                        "SMS.ir verification API returned unsuccessful status. status={}, body={}",
                        parsedStatus,
                        responseBody,
                    )
                    throw ExternalServiceException("SMS.ir")
                }
            }
        } catch (ex: ExternalServiceException) {
            throw ex
        } catch (ex: IOException) {
            logger.error("Failed to connect to SMS.ir verification API.", ex)
            throw ExternalServiceException("SMS.ir")
        } catch (ex: RuntimeException) {
            logger.error("Failed to process SMS.ir verification API response.", ex)
            throw ExternalServiceException("SMS.ir")
        }
    }

    private data class SmsIrVerifyRequest(
        val mobile: String,
        val templateId: Int,
        val parameters: List<SmsIrVerifyParameter>,
    )

    private data class SmsIrVerifyParameter(
        val name: String,
        val value: String,
    )

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val logger = LoggerFactory.getLogger(SmsProviderDeliveryAdapter::class.java)
        private const val SUCCESS_STATUS = 1
    }
}

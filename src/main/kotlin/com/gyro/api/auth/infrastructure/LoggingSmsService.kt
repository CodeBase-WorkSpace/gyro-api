package com.gyro.api.auth.infrastructure

import com.gyro.api.auth.application.SmsService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class LoggingSmsService : SmsService {
    override fun sendVerificationCode(phoneNumber: String, code: String) {
        logger.info("SMS delivery is not configured. Verification code generated for phoneNumber={}", phoneNumber)
        logger.debug("Development SMS verification code for {}: {}", phoneNumber, code)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(LoggingSmsService::class.java)
    }
}

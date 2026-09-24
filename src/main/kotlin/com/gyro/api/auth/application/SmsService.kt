package com.gyro.api.auth.application

interface SmsService {
    fun sendVerificationCode(phoneNumber: String, code: String)
}

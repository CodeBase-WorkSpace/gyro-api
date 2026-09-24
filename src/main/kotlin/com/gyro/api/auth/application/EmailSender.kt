package com.gyro.api.auth.application

interface EmailSender {
    fun send(message: EmailMessage)
}

data class EmailMessage(
    val to: String,
    val subject: String,
    val html: String,
)

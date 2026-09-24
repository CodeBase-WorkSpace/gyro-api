package com.gyro.api.common.id

import java.util.UUID

object UuidParser {
    fun parse(value: String): UUID? {
        return runCatching { UUID.fromString(value) }.getOrNull()
    }

    fun requireUuid(
        value: String,
        fieldName: String = "id",
    ): UUID {
        return parse(value) ?: throw IllegalArgumentException("$fieldName must be a valid UUID.")
    }
}

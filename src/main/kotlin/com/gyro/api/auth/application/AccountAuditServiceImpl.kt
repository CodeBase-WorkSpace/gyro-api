package com.gyro.api.auth.application

import com.gyro.api.auth.domain.AccountAuditEvent
import com.gyro.api.auth.domain.AccountAuditEventType
import com.gyro.api.auth.infrastructure.AccountAuditEventRepository
import com.gyro.api.common.request.RequestIds
import com.gyro.api.common.time.TimeProvider
import org.springframework.stereotype.Service
import java.util.*

@Service
class AccountAuditServiceImpl(
    private val repository: AccountAuditEventRepository,
    private val timeProvider: TimeProvider,
) : AccountAuditService {

    override fun record(
        actorUserId: UUID?,
        targetUserId: UUID,
        eventType: AccountAuditEventType,
        reason: String?,
        metadata: Map<String, Any?>,
    ) {
        repository.save(
            AccountAuditEvent(
                actorUserId = actorUserId,
                targetUserId = targetUserId,
                eventType = eventType,
                reason = reason?.take(MAX_REASON_LENGTH),
                metadata = redact(metadata).toSortedMap(),
                requestId = RequestIds.current(),
                createdAt = timeProvider.now(),
            )
        )
    }

    private fun redact(metadata: Map<String, Any?>): Map<String, Any?> {
        return metadata.mapNotNull { (key, value) ->
            if (key.isSensitiveKey()) {
                null
            } else {
                key to value.redactedValue()
            }
        }.toMap()
    }

    private fun Any?.redactedValue(): Any? {
        return when (this) {
            is Map<*, *> -> {
                val stringKeyed = entries.mapNotNull { (key, value) ->
                    (key as? String)?.let { it to value }
                }.toMap()
                redact(stringKeyed)
            }
            is Iterable<*> -> map { it.redactedValue() }
            else -> this
        }
    }

    private fun String.isSensitiveKey(): Boolean {
        val normalized = lowercase()
        return SENSITIVE_KEY_PARTS.any { normalized.contains(it) }
    }

    private companion object {
        private const val MAX_REASON_LENGTH = 255
        private val SENSITIVE_KEY_PARTS = setOf(
            "password",
            "token",
            "secret",
            "code",
            "credential",
            "hash",
        )
    }
}

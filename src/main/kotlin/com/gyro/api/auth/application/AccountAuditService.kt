package com.gyro.api.auth.application

import com.gyro.api.auth.domain.AccountAuditEventType
import java.util.UUID

interface AccountAuditService {
    fun record(
        actorUserId: UUID?,
        targetUserId: UUID,
        eventType: AccountAuditEventType,
        reason: String? = null,
        metadata: Map<String, Any?> = emptyMap(),
    )
}

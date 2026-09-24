package com.gyro.api.auth.infrastructure

import com.gyro.api.auth.domain.AccountAuditEvent
import com.gyro.api.auth.domain.AccountAuditEventType
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AccountAuditEventRepository : JpaRepository<AccountAuditEvent, UUID> {
    fun countByTargetUserIdAndEventType(
        targetUserId: UUID,
        eventType: AccountAuditEventType,
    ): Long
}

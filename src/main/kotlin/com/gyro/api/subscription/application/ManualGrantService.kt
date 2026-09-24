package com.gyro.api.subscription.application

import com.gyro.api.auth.infrastructure.UserRepository
import com.gyro.api.common.error.*
import com.gyro.api.common.observability.StageLog
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.subscription.domain.ManualGrant
import com.gyro.api.subscription.domain.ManualGrantReason
import com.gyro.api.subscription.infrastructure.ManualGrantRepository
import com.gyro.api.subscription.infrastructure.SubscriptionPlanRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
class ManualGrantService(
    private val manualGrantRepository: ManualGrantRepository,
    private val lifecycleService: SubscriptionLifecycleService,
    private val planRepository: SubscriptionPlanRepository,
    private val userRepository: UserRepository,
    private val timeProvider: TimeProvider,
    private val promotionService: PromotionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun createGrant(
        userId: UUID,
        planId: Long,
        durationDays: Int,
        reason: ManualGrantReason,
        grantedBy: UUID,
        reasonNote: String?,
        promotionCode: String? = null,
    ): ManualGrant {
        return StageLog.around(
            logger = logger,
            event = "manual_grant_create",
            stage = "service",
            fields = mapOf(
                "actorRole" to "ADMIN",
                "targetUserId" to userId.toString(),
                "planId" to planId,
                "durationDays" to durationDays,
            ),
        ) {
            validateGrantCreation(userId, planId, durationDays, reasonNote)

            val grant = lifecycleService.grantAccess(
                userId = userId,
                planId = planId,
                durationDays = durationDays,
                reason = reason,
                grantedBy = grantedBy,
                reasonNote = reasonNote,
            )
            promotionCode?.takeIf { it.isNotBlank() }?.let { code ->
                promotionService.recordManualGrantRedemption(code, userId, planId, requireNotNull(grant.id))
            }

            log.atInfo()
                .addKeyValue("event", "manual_grant_create")
                .addKeyValue("stage", "service_completed")
                .addKeyValue("outcome", "succeeded")
                .addKeyValue("grantId", grant.id.toString())
                .addKeyValue("actorRole", "ADMIN")
                .log("Manual grant created.")

            grant
        }
    }

    @Transactional
    fun extendGrant(
        grantId: UUID,
        additionalDays: Int,
        adminId: UUID,
        reason: String,
    ): ManualGrant {
        return StageLog.around(
            logger = logger,
            event = "manual_grant_extend",
            stage = "service",
            fields = mapOf(
                "actorRole" to "ADMIN",
                "grantId" to grantId.toString(),
                "additionalDays" to additionalDays,
            ),
        ) {
            validateExtension(additionalDays, reason)
            val saved = lifecycleService.extendGrant(
                grantId = grantId,
                additionalDays = additionalDays,
                adminId = adminId,
                reason = reason,
            )

            log.atInfo()
                .addKeyValue("event", "manual_grant_extend")
                .addKeyValue("stage", "service_completed")
                .addKeyValue("outcome", "succeeded")
                .addKeyValue("grantId", grantId.toString())
                .addKeyValue("actorRole", "ADMIN")
                .log("Manual grant extended.")

            saved
        }
    }

    @Transactional
    fun revokeGrant(grantId: UUID, revokedBy: UUID, reason: String) {
        StageLog.around(
            logger = logger,
            event = "manual_grant_revoke",
            stage = "service",
            fields = mapOf(
                "actorRole" to "ADMIN",
                "grantId" to grantId.toString(),
            ),
        ) {
            if (reason.isBlank()) {
                throw GrantReasonRequiredException()
            }
            lifecycleService.revokeGrant(grantId, revokedBy, reason)

            log.atInfo()
                .addKeyValue("event", "manual_grant_revoke")
                .addKeyValue("stage", "service_completed")
                .addKeyValue("outcome", "succeeded")
                .addKeyValue("grantId", grantId.toString())
                .addKeyValue("actorRole", "ADMIN")
                .log("Manual grant revoked.")
        }
    }

    fun expireGrants() {
        val now = timeProvider.now()
        val expiredGrantIds = manualGrantRepository.findExpiredGrantIds(now, PageRequest.of(0, EXPIRY_BATCH_SIZE))

        for (grantId in expiredGrantIds) {
            try {
                lifecycleService.expireGrant(grantId)
            } catch (ex: RuntimeException) {
                log.atWarn()
                    .addKeyValue("event", "manual_grant_expire")
                    .addKeyValue("stage", "service_failed")
                    .addKeyValue("outcome", "failed")
                    .addKeyValue("grantId", grantId.toString())
                    .setCause(ex)
                    .log("Failed to expire manual grant.")
                continue
            }

            log.atInfo()
                .addKeyValue("event", "manual_grant_expire")
                .addKeyValue("stage", "service_completed")
                .addKeyValue("outcome", "succeeded")
                .addKeyValue("grantId", grantId.toString())
                .log("Expired manual grant.")
        }
    }

    @Transactional(readOnly = true)
    fun findActiveGrants(userId: UUID): List<ManualGrant> {
        val now = timeProvider.now()
        return manualGrantRepository.findActiveByUserId(userId, now)
    }

    @Transactional(readOnly = true)
    fun findGrants(userId: UUID): List<ManualGrant> = manualGrantRepository.findByUserIdOrderByCreatedAtDesc(userId)

    private fun validateGrantCreation(
        userId: UUID,
        planId: Long,
        durationDays: Int?,
        reasonNote: String?,
    ) {
        if (reasonNote.isNullOrBlank()) {
            throw GrantReasonRequiredException()
        }

        if (!userRepository.existsById(userId)) {
            throw DeletedUserException()
        }

        val plan = planRepository.findById(planId)
            .orElseThrow { ResourceNotFoundException("Subscription plan") }

        if (!plan.active) {
            throw InactivePlanException()
        }

        if (durationDays == null || durationDays <= 0) {
            throw GrantExpiryInPastException()
        }
    }

    private fun validateExtension(additionalDays: Int, reason: String) {
        if (reason.isBlank()) {
            throw GrantReasonRequiredException()
        }
        if (additionalDays <= 0) {
            throw GrantExpiryInPastException()
        }
    }

    companion object {
        private const val EXPIRY_BATCH_SIZE = 100
        private val logger = LoggerFactory.getLogger(ManualGrantService::class.java)
    }
}

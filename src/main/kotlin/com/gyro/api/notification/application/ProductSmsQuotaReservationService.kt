package com.gyro.api.notification.application

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.jooq.Tables.NOTIFICATION_SMS_DAILY_QUOTAS
import com.gyro.api.notification.config.NotificationProperties
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Reserves quota for a provider-call attempt, rather than for a successful SMS.
 * A network or provider failure after the call starts still consumes the reservation so retries
 * cannot bypass the product cost ceiling. The global row serializes reservations across workers.
 */
@Service
class ProductSmsQuotaReservationService(
    private val dsl: DSLContext,
    private val time: TimeProvider,
    private val properties: NotificationProperties,
) {
    @Transactional
    fun reserveProviderAttempt(userId: UUID): Boolean {
        val timestamp = time.now().atOffset(ZoneOffset.UTC)
        val quotaDate = timestamp.toLocalDate()
        if (!reserve(quotaDate, GLOBAL_QUOTA_KEY, properties.productSmsDailyGlobalCap, timestamp)) return false
        if (reserve(quotaDate, userQuotaKey(userId), properties.productSmsMandatoryUserDailyCap, timestamp)) return true

        check(releaseGlobalReservation(quotaDate) == 1) {
            "Global SMS quota reservation disappeared before the user quota check completed"
        }
        return false
    }

    private fun reserve(quotaDate: LocalDate, quotaKey: String, cap: Int, timestamp: OffsetDateTime): Boolean =
        dsl.insertInto(QUOTAS)
            .columns(QUOTA_DATE, QUOTA_KEY, USED_COUNT, UPDATED_AT)
            .values(quotaDate, quotaKey, 1, timestamp)
            .onConflict(QUOTA_DATE, QUOTA_KEY)
            .doUpdate()
            .set(USED_COUNT, USED_COUNT.plus(1))
            .set(UPDATED_AT, timestamp)
            .where(USED_COUNT.lt(cap))
            .returning(USED_COUNT)
            .fetchOne() != null

    private fun releaseGlobalReservation(quotaDate: LocalDate): Int =
        dsl.update(QUOTAS)
            .set(USED_COUNT, USED_COUNT.minus(1))
            .where(QUOTA_DATE.eq(quotaDate).and(QUOTA_KEY.eq(GLOBAL_QUOTA_KEY)).and(USED_COUNT.gt(0)))
            .execute()

    private fun userQuotaKey(userId: UUID): String = "user:$userId"

    private companion object {
        val QUOTAS = NOTIFICATION_SMS_DAILY_QUOTAS
        val QUOTA_DATE = QUOTAS.QUOTA_DATE
        val QUOTA_KEY = QUOTAS.QUOTA_KEY
        val USED_COUNT = QUOTAS.USED_COUNT
        val UPDATED_AT = QUOTAS.UPDATED_AT
        const val GLOBAL_QUOTA_KEY = "global"
    }
}

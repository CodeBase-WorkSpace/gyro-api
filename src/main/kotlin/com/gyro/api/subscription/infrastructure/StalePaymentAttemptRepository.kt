package com.gyro.api.subscription.infrastructure

import com.gyro.api.jooq.Tables.PAYMENT_ATTEMPTS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*

@Repository
class StalePaymentAttemptRepository(
    private val dsl: DSLContext,
) {
    /**
     * Claims and terminalizes one ordered batch in a single PostgreSQL statement. Rows already
     * locked by verification or another cleanup worker are skipped, and the repeated status
     * predicate prevents a payment that was verified concurrently from being overwritten.
     */
    fun markStaleBefore(threshold: Instant, now: Instant, batchSize: Int): List<UUID> {
        val candidates = DSL.name("stale_payment_candidates")
        val candidateTable = DSL.table(candidates)
        val candidateId = DSL.field(DSL.name("stale_payment_candidates", "id"), UUID::class.java)
        val candidateIds = dsl.select(PAYMENT_ATTEMPTS.ID)
            .from(PAYMENT_ATTEMPTS)
            .where(
                PAYMENT_ATTEMPTS.STATUS.eq(PENDING_STATUS)
                    .and(PAYMENT_ATTEMPTS.CREATED_AT.lt(threshold.toOffsetDateTime())),
            )
            .orderBy(PAYMENT_ATTEMPTS.CREATED_AT, PAYMENT_ATTEMPTS.ID)
            .limit(batchSize)
            .forUpdate()
            .skipLocked()

        return dsl.with(candidates)
            .asMaterialized(candidateIds)
            .update(PAYMENT_ATTEMPTS)
            .set(PAYMENT_ATTEMPTS.STATUS, STALE_STATUS)
            .set(PAYMENT_ATTEMPTS.UPDATED_AT, now.toOffsetDateTime())
            .from(candidateTable)
            .where(
                PAYMENT_ATTEMPTS.ID.eq(candidateId)
                    .and(PAYMENT_ATTEMPTS.STATUS.eq(PENDING_STATUS)),
            )
            .returning(PAYMENT_ATTEMPTS.ID)
            .fetch(PAYMENT_ATTEMPTS.ID)
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    private companion object {
        private const val PENDING_STATUS = "PENDING"
        private const val STALE_STATUS = "STALE"
    }
}

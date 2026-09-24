package com.gyro.api.goal.application.coach

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.diary.application.CoachInsightImpressionMetrics
import com.gyro.api.diary.application.DashboardInsightKind
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID
import tools.jackson.databind.ObjectMapper

data class CoachIssuedImpressionSnapshot(
    val impressions: Map<String, DashboardInsightKind>,
    val expiresAt: Instant,
)

enum class CoachIssuedImpressionVerificationOutcome { ISSUED, NOT_ISSUED, ERROR }

data class CoachIssuedImpressionVerification(
    val outcome: CoachIssuedImpressionVerificationOutcome,
    val kind: DashboardInsightKind? = null,
)

/**
 * Keeps the two most recently returned Coach observation sets independently of
 * the state cache. A rendered response therefore remains verifiable after one
 * mutation invalidates and reranks the live Coach state.
 */
@Service
class CoachIssuedImpressionRegistry(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val metrics: CoachInsightImpressionMetrics,
    private val timeProvider: TimeProvider,
    @Value("\${app.nutrition-coach.impression-issuance-ttl:10m}")
    private val ttl: Duration,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val issueScript = DefaultRedisScript(ISSUE_SCRIPT, Long::class.java)

    fun issue(
        userId: UUID,
        impressions: Map<String, DashboardInsightKind>,
    ) {
        if (impressions.isEmpty()) return
        try {
            val identity = objectMapper.writeValueAsString(impressions)
            val snapshot = objectMapper.writeValueAsString(
                CoachIssuedImpressionSnapshot(
                    impressions = impressions,
                    expiresAt = timeProvider.now().plus(ttl),
                ),
            )
            val result = redisTemplate.execute(
                issueScript,
                listOf(keyFor(userId)),
                identity,
                snapshot,
                ttl.toMillis().toString(),
            )
            check(result == 1L) { "Coach impression issuance script returned no result." }
        } catch (exception: Exception) {
            metrics.issuanceFailed(ISSUANCE_OPERATION_ISSUE)
            logger.warn(
                "Coach impression issuance write failed for userId={}: {}",
                userId,
                exception.message,
            )
        }
    }

    fun verify(
        userId: UUID,
        impressionId: String,
    ): CoachIssuedImpressionVerification {
        return try {
            val values = redisTemplate.opsForHash<String, String>().multiGet(
                keyFor(userId),
                listOf(CURRENT_FIELD, PREVIOUS_FIELD),
            )
            val now = timeProvider.now()
            values.orEmpty().filterNotNull().forEach { json ->
                val snapshot = objectMapper.readValue(
                    json,
                    CoachIssuedImpressionSnapshot::class.java,
                )
                snapshot.impressions[impressionId]
                    ?.takeIf { snapshot.expiresAt.isAfter(now) }
                    ?.let { kind ->
                    return CoachIssuedImpressionVerification(
                        outcome = CoachIssuedImpressionVerificationOutcome.ISSUED,
                        kind = kind,
                    )
                }
            }
            CoachIssuedImpressionVerification(
                CoachIssuedImpressionVerificationOutcome.NOT_ISSUED,
            )
        } catch (exception: Exception) {
            metrics.issuanceFailed(ISSUANCE_OPERATION_VERIFY)
            logger.warn(
                "Coach impression issuance lookup failed for userId={}: {}",
                userId,
                exception.message,
            )
            CoachIssuedImpressionVerification(
                CoachIssuedImpressionVerificationOutcome.ERROR,
            )
        }
    }

    fun keyFor(userId: UUID): String = "$KEY_PREFIX$userId"

    companion object {
        const val ISSUANCE_OPERATION_ISSUE = "issuance_issue"
        const val ISSUANCE_OPERATION_VERIFY = "issuance_verify"
        const val KEY_PREFIX = "nutrition-coach:issued-impressions:v1:"
        private const val CURRENT_FIELD = "current"
        private const val PREVIOUS_FIELD = "previous"
        private const val ISSUE_SCRIPT =
            "local currentIdentity = redis.call('HGET', KEYS[1], 'current_identity'); " +
                "if currentIdentity ~= ARGV[1] then " +
                "local current = redis.call('HGET', KEYS[1], 'current'); " +
                "if current then redis.call('HSET', KEYS[1], 'previous', current); end; " +
                "redis.call('HSET', KEYS[1], 'current_identity', ARGV[1]); end; " +
                "redis.call('HSET', KEYS[1], 'current', ARGV[2]); " +
                "redis.call('PEXPIRE', KEYS[1], ARGV[3]); return 1"
    }
}

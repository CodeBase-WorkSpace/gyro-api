package com.gyro.api.diary.application

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.gyro.api.goal.application.recalibration.RecalibrationConfidence
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.reflect.full.memberProperties
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the Coach observation transport so it cannot drift back into a wide bag of
 * mostly-null fields.
 *
 * The sealed hierarchy only keeps that promise while someone checks it. Three things go
 * wrong quietly otherwise: a new kind forgets to register a Jackson subtype and
 * serializes without a usable discriminator; a field meaningful to one kind gets added
 * to a shared type "for convenience" and becomes null everywhere else; or a kind omits a
 * cross-cutting field and every consumer has to learn that exception by reading the
 * class. [expectedFields] is the single place the answer lives, and a diff to it is the
 * signal for a reviewer to ask which of those three just happened.
 */
class DashboardInsightContractTest {
    @Test
    fun `every kind has exactly one registered subtype named after it`() {
        val registered = DashboardInsight::class.java
            .getAnnotation(JsonSubTypes::class.java)
            .value
            .associate { it.name to it.value }

        assertEquals(
            DashboardInsightKind.entries.mapTo(mutableSetOf()) { it.name },
            registered.keys,
            "every kind must register exactly one Jackson subtype under its own name",
        )
        registered.forEach { (name, type) ->
            val instance = sample(DashboardInsightKind.valueOf(name))
            assertEquals(
                type.java,
                instance::class.java,
                "the subtype registered for $name must be the class that reports that kind",
            )
        }
    }

    @Test
    fun `every kind exposes exactly the fields declared for it`() {
        DashboardInsightKind.entries.forEach { kind ->
            assertEquals(
                expectedFields.getValue(kind),
                fieldsOf(sample(kind)),
                "the field set of $kind changed; put a kind-specific field on that kind " +
                    "alone, and a cross-cutting one on DashboardInsight",
            )
        }
    }

    @Test
    fun `the shared fields are answered by every kind`() {
        DashboardInsightKind.entries.forEach { kind ->
            val insight = sample(kind)
            assertEquals(kind, insight.kind)
            assertTrue(insight.impressionId.isNotEmpty(), "$kind must carry an impression identity")
            // basis and trend are declared on the interface, so the compiler already
            // forced an answer. This asserts the answers themselves stay meaningful.
            assertTrue(
                kind in kindsWithoutBasis || insight.basis != null,
                "$kind must declare the evidence class behind its value",
            )
            if (kind in kindsWithoutBasis) {
                assertNull(insight.basis, "$kind rests on no evidence and must report no basis")
            }
        }
    }

    @Test
    fun `only the two directional kinds may report a trend`() {
        DashboardInsightKind.entries.forEach { kind ->
            val trend = sample(kind).trend
            if (kind in directionalKinds) {
                assertNotNull(trend, "$kind reports a direction and must be able to carry one")
            } else {
                // The frontend renders UP as improvement. A neutral observation that set
                // this would claim progress it never measured.
                assertNull(trend, "$kind makes no directional claim and must report no trend")
            }
        }
    }

    /** The properties Jackson turns into JSON keys for these data classes. */
    private fun fieldsOf(insight: DashboardInsight): Set<String> =
        insight::class.memberProperties.mapTo(mutableSetOf()) { it.name }

    private companion object {
        /** Kinds that count records or say nothing, so they rest on no evidence class. */
        val kindsWithoutBasis = setOf(
            DashboardInsightKind.LOGGING_STREAK,
            DashboardInsightKind.COACH_TIP,
        )

        /** Kinds whose whole point is a direction the frontend may badge. */
        val directionalKinds = setOf(
            DashboardInsightKind.SCORE_TREND,
            DashboardInsightKind.PROTEIN_CONSISTENCY,
        )

        val shared = setOf("kind", "impressionId", "value", "basis", "trend")
        val period = setOf("periodStart", "periodEnd")

        val expectedFields: Map<DashboardInsightKind, Set<String>> = mapOf(
            DashboardInsightKind.CALORIE_ADHERENCE to shared + period + setOf(
                "averageIntakeCalories", "averageTargetCalories", "deltaPercent", "loggedDayCount",
            ),
            DashboardInsightKind.SCORE_TREND to shared + period + setOf(
                "loggedDayCount", "previousLoggedDayCount",
            ),
            DashboardInsightKind.BEST_DAY to shared + period + setOf("date", "loggedDayCount"),
            DashboardInsightKind.PROTEIN_CONSISTENCY to shared + period + setOf("loggedDayCount"),
            DashboardInsightKind.LOGGING_STREAK to shared + setOf("capped"),
            DashboardInsightKind.COACH_TIP to shared,
            DashboardInsightKind.MEASURED_TDEE to shared + period + setOf(
                "confidence", "estimatorVersion", "displayPolicyVersion", "windowDays",
                "loggedDayCount", "weighInDayCount", "weightSpanDays",
            ),
            DashboardInsightKind.WEEKEND_GAP to shared + period + setOf(
                "weekendTargetDeltaPercent", "weekdayTargetDeltaPercent", "weekendLoggedDayCount",
                "weekdayLoggedDayCount", "loggedDayCount", "windowDays",
            ),
            DashboardInsightKind.TREND_EXPLANATION to shared + period + setOf(
                "deltaPercent", "weightTrendKgPerWeek", "averageIntakeCalories",
                "averageTargetCalories", "loggedDayCount", "weighInDayCount", "weightSpanDays",
                "windowDays", "confidence",
            ),
            // The forecast keeps its evidence in one nested model rather than a dozen
            // loose nullable columns on the shared transport.
            DashboardInsightKind.GOAL_FORECAST to shared + setOf("goalForecast"),
        )

        val start: LocalDate = LocalDate.parse("2026-07-16")
        val end: LocalDate = LocalDate.parse("2026-07-22")

        fun sample(kind: DashboardInsightKind): DashboardInsight = when (kind) {
            DashboardInsightKind.CALORIE_ADHERENCE -> CalorieAdherenceInsight(
                "id", 1, 1800, 2000, BigDecimal("-10"), 5, start, end,
            )
            DashboardInsightKind.SCORE_TREND -> ScoreTrendInsight(
                "id", 1, DashboardInsightTrend.UP, 5, 5, start, end,
            )
            DashboardInsightKind.BEST_DAY -> BestDayInsight("id", 1, end, 5, start, end)
            DashboardInsightKind.PROTEIN_CONSISTENCY -> ProteinConsistencyInsight(
                "id", 1, DashboardInsightTrend.STABLE, 5, start, end,
            )
            DashboardInsightKind.LOGGING_STREAK -> LoggingStreakInsight("id", 7, false)
            DashboardInsightKind.COACH_TIP -> CoachTipInsight("id", 1)
            DashboardInsightKind.MEASURED_TDEE -> MeasuredTdeeInsight(
                "id", 2300, RecalibrationConfidence.MEDIUM, "OLS_7700_V1", "V1",
                14, 10, 4, 10, start, end,
            )
            DashboardInsightKind.WEEKEND_GAP -> WeekendGapInsight(
                "id", 10, BigDecimal.TEN, BigDecimal.ZERO, 4, 10, 14, 14, start, end,
            )
            DashboardInsightKind.TREND_EXPLANATION -> TrendExplanationInsight(
                "id", -10, BigDecimal("-10"), BigDecimal("0.5"), 1800, 2000,
                10, 4, 10, 14, start, end, RecalibrationConfidence.MEDIUM,
            )
            DashboardInsightKind.GOAL_FORECAST -> GoalForecastInsight(
                "id", 40,
                GoalForecast(
                    status = GoalForecastStatus.FLAT_TREND,
                    originalTargetDate = end,
                    forecastTargetDate = null,
                    delayDays = null,
                    startWeightKg = BigDecimal("90.000"),
                    targetWeightKg = BigDecimal("80.000"),
                    fittedWeightKg = null,
                    observedKgPerWeek = null,
                    progressPercent = BigDecimal("0.00"),
                    evidenceStart = null,
                    evidenceEnd = null,
                    weighInDayCount = 0,
                    weightSpanDays = 0,
                    milestones = listOf(25, 50, 75, 100).map { progressPercent ->
                        GoalForecastMilestone(
                            progressPercent = progressPercent,
                            targetWeightKg = BigDecimal("85.000"),
                            plannedDate = end,
                            forecastDate = null,
                            state = GoalForecastMilestoneState.UPCOMING,
                        )
                    },
                ),
            )
        }
    }
}

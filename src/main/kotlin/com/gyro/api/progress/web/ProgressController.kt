package com.gyro.api.progress.web

import com.gyro.api.common.observability.HealthTrackingObservability
import com.gyro.api.common.observability.StageLog
import com.gyro.api.daily_score.application.DailyScoreAnalyticsService
import com.gyro.api.daily_score.web.dto.DailyScoreAnalyticsResponse
import com.gyro.api.daily_score.web.dto.toResponse
import com.gyro.api.goal.application.nutrition_plan.NutritionPlanService
import com.gyro.api.progress.application.*
import com.gyro.api.progress.web.dto.*
import com.gyro.api.weight.domain.toKilograms
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.*

@Validated
@RestController
@RequestMapping("\${app.api.base-path}/progress")
class ProgressController(
    private val progressPeriodResolver: ProgressPeriodResolver,
    private val progressReadService: ProgressReadService,
    private val nutritionPlanService: NutritionPlanService,
    private val dailyScoreAnalyticsService: DailyScoreAnalyticsService,
    private val healthTrackingObservability: HealthTrackingObservability,
) {
    @GetMapping("/daily-scores")
    fun getDailyScoreAnalytics(
        @AuthenticationPrincipal userId: String,
        @RequestParam from: LocalDate,
        @RequestParam to: LocalDate,
    ): DailyScoreAnalyticsResponse {
        return StageLog.around(
            logger = logger,
            event = ANALYTICS_LOG_EVENT,
            stage = "range_query",
            fields = mapOf("rangePresent" to true),
        ) {
            dailyScoreAnalyticsService.summary(
                userId = UUID.fromString(userId),
                from = from,
                to = to,
            ).toResponse()
        }
    }

    @PostMapping("/nutrition/batch")
    fun getNutritionProgressBatch(
        @AuthenticationPrincipal userId: String,
        @Valid @RequestBody request: NutritionBatchProgressRequestBody,
    ): NutritionBatchProgressResponse {
        return StageLog.around(
            logger = logger,
            event = PROGRESS_LOG_EVENT,
            stage = "nutrition_batch_query",
            fields = mapOf("rangeCount" to request.ranges.size),
        ) {
            val ownerUserId = UUID.fromString(userId)
            val resolvedRequests = progressPeriodResolver.resolveNutritionBatchPeriods(
                userId = ownerUserId,
                requests = request.ranges.map {
                    NutritionBatchProgressRequest(
                        requestId = it.requestId,
                        period = it.period,
                        anchor = it.anchor,
                        month = it.month,
                        from = it.from,
                        to = it.to,
                    )
                },
            )

            healthTrackingObservability.observeProgressQuery(
                userId = ownerUserId,
                domain = "nutrition",
                mode = "batch",
                period = HealthTrackingObservability.rangeType(resolvedRequests.map { it.period }),
                rangeCount = resolvedRequests.size,
            ) {
                progressReadService.nutritionProgressBatch(
                    userId = ownerUserId,
                    requests = resolvedRequests,
                ).toBatchResponse()
            }
        }
    }

    @GetMapping("/nutrition")
    fun getNutritionProgress(
        @AuthenticationPrincipal userId: String,
        @RequestParam period: NutritionProgressPeriod,
        @RequestParam(required = false) anchor: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") month: YearMonth?,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
    ): NutritionProgressResponse {
        return StageLog.around(
            logger = logger,
            event = PROGRESS_LOG_EVENT,
            stage = "nutrition_range_query",
            fields = progressQueryFields(period = period.name, rangeCount = 1),
        ) {
            val ownerUserId = UUID.fromString(userId)
            val resolvedPeriod = progressPeriodResolver.resolveNutritionPeriod(
                userId = ownerUserId,
                period = period,
                anchor = anchor,
                month = month,
                from = from,
                to = to,
            )
            healthTrackingObservability.observeProgressQuery(
                userId = ownerUserId,
                domain = "nutrition",
                mode = "single",
                period = HealthTrackingObservability.rangeType(resolvedPeriod.period),
                rangeCount = 1,
            ) {
                nutritionProgressResponse(
                    ownerUserId = ownerUserId,
                    resolvedPeriod = resolvedPeriod,
                )
            }
        }
    }

    @GetMapping("/weekly")
    fun getWeeklyProgress(
        @AuthenticationPrincipal userId: String,
        @RequestParam(required = false) anchor: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") month: YearMonth?,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
    ): WeeklyProgressResponse {
        return StageLog.around(
            logger = logger,
            event = PROGRESS_LOG_EVENT,
            stage = "weekly_range_query",
            fields = mapOf("rangeCount" to 1),
        ) {
            val ownerUserId = UUID.fromString(userId)
            val resolvedPeriod = progressPeriodResolver.resolveNutritionWeeklyAlias(
                userId = ownerUserId,
                anchor = anchor,
                month = month,
                from = from,
                to = to,
            )
            healthTrackingObservability.observeProgressQuery(
                userId = ownerUserId,
                domain = "weekly",
                mode = "single",
                period = HealthTrackingObservability.rangeType(resolvedPeriod.period),
                rangeCount = 1,
            ) {
                val weightProgress = progressReadService.weightProgress(
                    userId = ownerUserId,
                    range = resolvedPeriod.range,
                )
                val targetWeightKg = nutritionPlanService.findActivePlan(
                    userId = ownerUserId,
                    activeOn = resolvedPeriod.range.to,
                )?.targetWeightKg()
                nutritionProgressResponse(
                    ownerUserId = ownerUserId,
                    resolvedPeriod = resolvedPeriod,
                ).toWeeklyResponse(
                    weight = weightProgress.toWeeklyWeightSummaryResponse(
                        targetWeightKg = targetWeightKg,
                    ),
                )
            }
        }
    }

    @PostMapping("/weight/batch")
    fun getWeightProgressBatch(
        @AuthenticationPrincipal userId: String,
        @Valid @RequestBody request: WeightBatchProgressRequestBody,
    ): WeightBatchProgressResponse {
        return StageLog.around(
            logger = logger,
            event = PROGRESS_LOG_EVENT,
            stage = "weight_batch_query",
            fields = mapOf("rangeCount" to request.ranges.size),
        ) {
            val ownerUserId = UUID.fromString(userId)
            val resolvedRequests = progressPeriodResolver.resolveWeightBatchPeriods(
                userId = ownerUserId,
                requests = request.ranges.map {
                    WeightBatchProgressRequest(
                        requestId = it.requestId,
                        period = it.period,
                        anchor = it.anchor,
                        from = it.from,
                        to = it.to,
                    )
                },
            )
            val results = healthTrackingObservability.observeProgressQuery(
                userId = ownerUserId,
                domain = "weight",
                mode = "batch",
                period = HealthTrackingObservability.rangeType(resolvedRequests.map { it.period }),
                rangeCount = resolvedRequests.size,
            ) {
                resolvedRequests.map { resolved ->
                    val progress = progressReadService.weightProgress(
                        userId = ownerUserId,
                        range = resolved.range,
                    )
                    WeightBatchProgressResultResponse(
                        requestId = resolved.requestId,
                        result = progress.toResponse(
                            period = resolved.period,
                            timezone = resolved.timezone,
                        ),
                    )
                }
            }

            WeightBatchProgressResultResponseList(results).toResponse()
        }
    }

    @GetMapping("/weight")
    fun getWeightProgress(
        @AuthenticationPrincipal userId: String,
        @RequestParam period: WeightProgressPeriod,
        @RequestParam(required = false) anchor: LocalDate?,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
    ): WeightProgressResponse {
        return StageLog.around(
            logger = logger,
            event = PROGRESS_LOG_EVENT,
            stage = "weight_range_query",
            fields = progressQueryFields(period = period.name, rangeCount = 1),
        ) {
            val ownerUserId = UUID.fromString(userId)
            val resolvedPeriod = progressPeriodResolver.resolveWeightPeriod(
                userId = ownerUserId,
                period = period,
                anchor = anchor,
                from = from,
                to = to,
            )
            healthTrackingObservability.observeProgressQuery(
                userId = ownerUserId,
                domain = "weight",
                mode = "single",
                period = HealthTrackingObservability.rangeType(resolvedPeriod.period),
                rangeCount = 1,
            ) {
                val progress = progressReadService.weightProgress(
                    userId = ownerUserId,
                    range = resolvedPeriod.range,
                )

                progress.toResponse(
                    period = resolvedPeriod.period,
                    timezone = resolvedPeriod.timezone,
                )
            }
        }
    }

    private fun nutritionProgressResponse(
        ownerUserId: UUID,
        resolvedPeriod: ResolvedNutritionProgressPeriod,
    ): NutritionProgressResponse {
        val progress = progressReadService.nutritionProgress(
            userId = ownerUserId,
            range = resolvedPeriod.range,
        )

        return progress.toResponse(
            period = resolvedPeriod.period,
            timezone = resolvedPeriod.timezone,
        )
    }

    private fun com.gyro.api.goal.application.nutrition_plan.NutritionPlanReadModel.targetWeightKg(): BigDecimal? {
        val targetWeight = targetWeight ?: return null
        return targetWeightUnit?.toKilograms(targetWeight)
    }

    private fun progressQueryFields(
        period: String,
        rangeCount: Int,
    ): Map<String, Any?> {
        return mapOf(
            "period" to period,
            "rangeCount" to rangeCount,
        )
    }

    companion object {
        private const val PROGRESS_LOG_EVENT = "progress"
        private const val ANALYTICS_LOG_EVENT = "analytics"
        private val logger = LoggerFactory.getLogger(ProgressController::class.java)
    }
}

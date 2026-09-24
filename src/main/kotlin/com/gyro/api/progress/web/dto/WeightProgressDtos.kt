package com.gyro.api.progress.web.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.gyro.api.common.trend.LinearTrendFit
import com.gyro.api.progress.application.WeightProgressPeriod
import com.gyro.api.progress.application.WeightProgressPoint
import com.gyro.api.progress.application.WeightProgressReadModel
import com.gyro.api.progress.application.WeightTrendDirection
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

data class WeightBatchProgressRequestBody(
    @field:NotEmpty
    @field:Size(max = 12)
    @field:Valid
    val ranges: List<WeightBatchProgressRangeRequest>,
)

data class WeightBatchProgressRangeRequest(
    @field:NotBlank
    @field:Size(max = 120)
    val requestId: String,

    @field:NotNull
    val period: WeightProgressPeriod,

    val anchor: LocalDate? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class WeightBatchProgressResponse(
    val results: List<WeightBatchProgressResultEnvelope>,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class WeightBatchProgressResultEnvelope(
    val requestId: String,
    val period: WeightProgressPeriod,
    val timezone: String,
    val from: LocalDate,
    val to: LocalDate,
    val latestMeasurementDate: LocalDate?,
    val points: List<WeightProgressPointResponse>,
    val summary: WeightProgressSummaryResponse,
    val trend: WeightTrendResponse?,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class WeightProgressResponse(
    val period: WeightProgressPeriod,
    val timezone: String,
    val from: LocalDate,
    val to: LocalDate,
    val latestMeasurementDate: LocalDate?,
    val points: List<WeightProgressPointResponse>,
    val summary: WeightProgressSummaryResponse,
    /**
     * Fitted line over this range, or null below three measured days.
     *
     * Sits beside [summary] rather than inside it: [summary] is a flat bag of scalar
     * endpoint facts, and nesting an all-or-nothing object with its own array would
     * change the shape its snapshot test exists to hold stable.
     *
     * The recalibration engine fits the same way over its own fixed window, so its
     * slope and this one describe different intervals and will not match. Nothing may
     * present this as the line behind a recalibration suggestion.
     */
    val trend: WeightTrendResponse?,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class WeightTrendResponse(
    val method: String,
    val slopeKgPerWeek: BigDecimal,
    val direction: WeightTrendDirection,
    val startValueKg: BigDecimal,
    val endValueKg: BigDecimal,
    /**
     * Fitted values at measured dates only. No synthetic daily points are emitted, so a
     * gap stays visible as horizontal spacing rather than reading as measurements that
     * were never taken. The segment drawn between two measured dates still represents
     * the fitted relationship across that interval.
     */
    val points: List<WeightTrendPointResponse>,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class WeightTrendPointResponse(
    val date: LocalDate,
    val fittedWeightKg: BigDecimal,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class WeightProgressPointResponse(
    val date: LocalDate,
    val weightKg: BigDecimal?,
    val hasMeasurement: Boolean,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class WeightProgressSummaryResponse(
    val startWeightKg: BigDecimal?,
    val endWeightKg: BigDecimal?,
    val absoluteChangeKg: BigDecimal?,
    val percentChange: BigDecimal?,
    val trendDirection: WeightTrendDirection,
    val measurementCount: Int,
    val missingDayCount: Int,
)

data class WeightBatchProgressResultResponse(
    val requestId: String,
    val result: WeightProgressResponse,
)

data class WeightBatchProgressResultResponseList(
    val results: List<WeightBatchProgressResultResponse>,
)

fun WeightProgressReadModel.toResponse(
    period: WeightProgressPeriod,
    timezone: String,
): WeightProgressResponse {
    val measurementsByDate = measurements.associateBy { it.date }
    val points = generateSequence(range.from) { current ->
        current.plusDays(1).takeIf { !it.isAfter(range.to) }
    }.map { date ->
        val measurement = measurementsByDate[date]
        WeightProgressPointResponse(
            date = date,
            weightKg = measurement?.weightKg,
            hasMeasurement = measurement != null,
        )
    }.toList()

    return WeightProgressResponse(
        period = period,
        timezone = timezone,
        from = range.from,
        to = range.to,
        latestMeasurementDate = latestMeasurementDate,
        points = points,
        summary = WeightProgressSummaryResponse(
            startWeightKg = startWeightKg,
            endWeightKg = endWeightKg,
            absoluteChangeKg = absoluteChangeKg,
            percentChange = percentageChange,
            trendDirection = trend,
            measurementCount = measurements.size,
            missingDayCount = missingDayCount,
        ),
        trend = trendFit?.toResponse(measurements),
    )
}

private fun LinearTrendFit.toResponse(
    measurements: List<WeightProgressPoint>,
): WeightTrendResponse {
    val measuredDates = measurements.map { it.date }.distinct().sorted()
    // Classify before rounding. 0.0497 kg/week is inside the dead zone but serializes
    // as 0.050, so rounding first would report a flat trend as a weekly gain — and the
    // Persian description is driven by direction, not by the number.
    val slopeKgPerWeek = slopePerDay.multiply(DAYS_PER_WEEK)

    return WeightTrendResponse(
        method = TREND_METHOD_OLS,
        slopeKgPerWeek = slopeKgPerWeek.setScale(3, RoundingMode.HALF_UP),
        direction = slopeKgPerWeek.toDirection(),
        startValueKg = valueAt(measuredDates.first()),
        endValueKg = valueAt(measuredDates.last()),
        points = measuredDates.map { date ->
            WeightTrendPointResponse(date = date, fittedWeightKg = valueAt(date))
        },
    )
}

/**
 * A dead zone, unlike [WeightProgressSummaryResponse.trendDirection], which takes the
 * strict sign of the endpoint change and so reads a 1 g difference as a direction.
 */
private fun BigDecimal.toDirection(): WeightTrendDirection = when {
    abs() < FLAT_SLOPE_THRESHOLD_KG_PER_WEEK -> WeightTrendDirection.FLAT
    signum() > 0 -> WeightTrendDirection.UP
    else -> WeightTrendDirection.DOWN
}

/** The only estimator this surface reports. Named so a rename cannot silently become an API change. */
const val TREND_METHOD_OLS = "OLS"

private val DAYS_PER_WEEK = BigDecimal(7)
private val FLAT_SLOPE_THRESHOLD_KG_PER_WEEK = BigDecimal("0.05")

fun WeightBatchProgressResultResponseList.toResponse(): WeightBatchProgressResponse {
    return WeightBatchProgressResponse(
        results = results.map { item ->
            WeightBatchProgressResultEnvelope(
                requestId = item.requestId,
                period = item.result.period,
                timezone = item.result.timezone,
                from = item.result.from,
                to = item.result.to,
                latestMeasurementDate = item.result.latestMeasurementDate,
                points = item.result.points,
                summary = item.result.summary,
                trend = item.result.trend,
            )
        },
    )
}
